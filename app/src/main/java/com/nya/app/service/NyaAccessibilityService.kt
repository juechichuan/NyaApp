package com.nya.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.os.bundleOf
import com.nya.app.NyaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "NyaA11yService"

/**
 * 核心服务：通过无障碍能力监听输入动作，自动在文本末尾追加自定义内容（默认"喵"）。
 *
 * 工作原理（停顿追加模式，最稳）：
 *   1. 监听 TYPE_VIEW_TEXT_CHANGED（用户在 EditText 里打字）
 *   2. 用户停顿 1200ms 不再输入 → 自动追加「喵」到末尾
 *   3. 用户继续打字 → 取消上次的追加任务（防止重复追加 / 误追加到错位置）
 *   4. 文本末尾已有「喵」 → 跳过追加
 *
 * 这种方案能确保用户点击发送按钮时，「喵」已经在文本末尾，发送出去的消息就带「喵」。
 * 相比"监听发送按钮点击后才追加"的旧方案（那时 App 已读完原文发出去了，喵进不了消息），可靠性大幅提升。
 *
 * 同时保留"发送按钮点击"作为兜底：少数 App 在 onClick 里二次读取文本，这时再追加一次也能生效。
 */
class NyaAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var masterEnabled = true
    @Volatile private var appendContent = "喵"
    @Volatile private var isGlobalMode = true

    // 停顿追加：用户停止输入 1200ms 后追加
    @Volatile private var pendingAppendRunnable: Runnable? = null
    @Volatile private var lastAppendedText: String? = null
    @Volatile private var appending = false

    // 当前焦点 EditText 缓存
    @Volatile private var lastFocusedEditor: AccessibilityNodeInfo? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val snap = (application as NyaApplication).prefs.snapshotBlocking()
        masterEnabled = snap.masterEnabled
        appendContent = snap.appendContent.ifBlank { "喵" }
        isGlobalMode = snap.isGlobalMode

        scope.launch {
            (application as NyaApplication).prefs.masterEnabled.collectLatest {
                withContext(Dispatchers.Main) { masterEnabled = it }
            }
        }
        scope.launch {
            (application as NyaApplication).prefs.appendContent.collectLatest {
                withContext(Dispatchers.Main) { appendContent = it.ifBlank { "喵" } }
            }
        }
        scope.launch {
            (application as NyaApplication).prefs.isGlobalMode.collectLatest {
                withContext(Dispatchers.Main) { isGlobalMode = it }
            }
        }
    }

    /**
     * 系统绑定服务后回调：动态配置 serviceInfo，强制拿到完整能力。
     * 某些 ROM（如 ColorOS 15）会忽略 XML 里的静态配置，必须在这里再设一次。
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching {
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY or
                        AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                notificationTimeout = 20L
                // 关键：必须显式声明这两个能力，否则 performAction / rootInActiveWindow 都不工作
                // 注意：canRetrieveWindowContent / canPerformGestures 在 API 33+ 通过 flags 隐式开启
            }
            this.serviceInfo = info
            Log.i(TAG, "onServiceConnected: serviceInfo 已动态配置")
        }.onFailure { Log.e(TAG, "onServiceConnected 配置 serviceInfo 失败", it) }
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        pendingAppendRunnable?.let { mainHandler.removeCallbacks(it) }
        scope.cancel()
    }

    // ============================
    //  核心入口：事件分发
    // ============================
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!masterEnabled) return

        val pkg = (event.packageName?.toString() ?: "").takeIf { it.isNotBlank() } ?: return
        if (!isGlobalMode) {
            // 非全局模式下，只在白名单 App 中生效（保留兼容，默认不会进入此分支）
            Log.d(TAG, "非全局模式，跳过 $pkg")
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> maybeCacheFocusedEditor(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 切页面，清掉缓存的 EditText 与待追加任务
                cancelPendingAppend()
                lastFocusedEditor = null
                lastAppendedText = null
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleButtonClickedAsFallback(event)
        }
    }

    // ============================
    //  停顿追加：核心
    // ============================
    private fun handleTextChanged(event: AccessibilityEvent) {
        val node = event.source ?: findFocusedEditText(rootInActiveWindow) ?: return
        if (!isEditable(node)) return
        if (isPasswordInput(node)) {
            Log.d(TAG, "密码/安全输入框，跳过")
            return
        }

        val currentText = node.text?.toString().orEmpty()
        if (currentText.isBlank()) {
            // 文本清空了，重置状态
            lastAppendedText = null
            cancelPendingAppend()
            return
        }

        // 如果末尾已经是「喵」，说明刚刚追加过；用户继续打字会触发新的 textChanged，
        // 这时去掉末尾的「喵」让用户接着编辑原始内容（可选行为：保留也行，看体验）
        // 这里选择保留策略：用户继续打字就取消上次追加任务，重新计时
        cancelPendingAppend()

        // 如果当前文本就等于上次追加后的文本，说明用户没再打字，不需要再追加
        if (currentText == lastAppendedText) {
            Log.d(TAG, "文本未变化（已是追加后版本），跳过")
            return
        }

        // 如果末尾已经是追加内容，跳过
        if (currentText.endsWith(appendContent)) {
            Log.d(TAG, "已经以「$appendContent」结尾，跳过追加")
            return
        }

        val editor = node
        val pendingText = currentText
        Log.d(TAG, "检测到文本变化，1200ms 后追加「$appendContent」 → 当前: \"$pendingText\"")

        val r = Runnable {
            appending = true
            try {
                // 重新读节点最新文本（用户可能在这 1.2s 内又改了）
                val latestText = editor.text?.toString().orEmpty()
                if (latestText.isBlank()) {
                    Log.d(TAG, "计时到达，但文本已清空，跳过")
                    return@Runnable
                }
                if (latestText.endsWith(appendContent)) {
                    Log.d(TAG, "计时到达，但末尾已是「$appendContent」，跳过")
                    return@Runnable
                }
                val newText = latestText + appendContent
                val ok = appendTextToNode(editor, newText)
                if (ok) {
                    lastAppendedText = newText
                    Log.i(TAG, "✓ 已追加「$appendContent」→ \"$newText\"")
                } else {
                    Log.w(TAG, "追加文本失败（performAction 返回 false）")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "追加文本异常", t)
            } finally {
                mainHandler.postDelayed({ appending = false }, 200)
            }
        }
        pendingAppendRunnable = r
        mainHandler.postDelayed(r, 1200)
    }

    private fun cancelPendingAppend() {
        pendingAppendRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingAppendRunnable = null
    }

    // ============================
    //  焦点缓存
    // ============================
    private fun maybeCacheFocusedEditor(event: AccessibilityEvent) {
        val node = event.source ?: return
        if (isEditable(node)) {
            lastFocusedEditor = node
            Log.d(TAG, "缓存焦点 EditText: ${node.className}")
        }
    }

    /** 在 root 节点下查找当前处于焦点的可编辑 EditText */
    private fun findFocusedEditText(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        if (isEditable(root) && root.isFocused) return root
        // 优先找 accessibility focus
        val focused = runCatching {
            root.findFocus(AccessibilityNodeInfo.ACCESSIBILITY_FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.ACCESSIBILITY_FOCUS)
        }.getOrNull()
        if (focused != null && isEditable(focused)) return focused
        // 退化：DFS 找第一个 editable
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (isEditable(n)) return n
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                queue.add(c)
            }
        }
        return null
    }

    // ============================
    //  兜底：发送按钮点击（少数 App 在 onClick 里二次读取文本，能再追一次）
    // ============================
    private fun handleButtonClickedAsFallback(event: AccessibilityEvent) {
        if (appending) return
        val clickedNode = event.source ?: return
        if (!isSendLikeButton(clickedNode)) return

        val editor = findFocusedEditText(rootInActiveWindow)
            ?: lastFocusedEditor
            ?: run {
                Log.d(TAG, "fallback: 没有找到输入框")
                return
            }
        if (isPasswordInput(editor)) return

        val currentText = editor.text?.toString().orEmpty()
        if (currentText.isBlank() || currentText.endsWith(appendContent)) return

        appending = true
        try {
            val newText = currentText + appendContent
            appendTextToNode(editor, newText)
            lastAppendedText = newText
            Log.i(TAG, "✓ fallback 已追加「$appendContent」→ \"$newText\"")
        } catch (t: Throwable) {
            Log.e(TAG, "fallback 追加异常", t)
        } finally {
            mainHandler.postDelayed({ appending = false }, 300)
        }
    }

    // ============================
    //  辅助方法
    // ============================

    /** 是否是"发送/确定/下一步/Send/Done"类按钮 */
    private fun isSendLikeButton(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) return false
        val text = (node.text?.toString() ?: "").trim()
        val desc = (node.contentDescription?.toString() ?: "").trim()
        val keywords = listOf(
            "发送", "确定", "完成", "搜索", "下一步", "前往", "发送消息", "发送消息按钮",
            "Send", "Send message", "Done", "Search", "Next", "Go", "Submit", "Enter", "Post",
            "评论", "发布", "回复", "提交", "Confirm", "OK", "传送", "纸飞机", "箭头"
        )
        if (keywords.any { text.equals(it, ignoreCase = true) }) return true
        if (keywords.any { desc.equals(it, ignoreCase = true) }) return true

        // 对于无文字的图标按钮，查子孙节点文案
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val cText = (c.text?.toString() ?: "").trim()
            val cDesc = (c.contentDescription?.toString() ?: "").trim()
            if (keywords.any { it.equals(cText, ignoreCase = true) || it.equals(cDesc, ignoreCase = true) }) {
                return true
            }
        }
        return false
    }

    private fun isEditable(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cls = node.className?.toString().orEmpty()
        return cls.contains("EditText") || cls.contains("TextInputEdit")
                || cls == "android.widget.EditText" || cls == "androidx.appcompat.widget.AppCompatEditText"
                || cls.contains("ComposeTextInput") || cls.contains("TextField")
    }

    /** 是否是密码输入框 */
    private fun isPasswordInput(node: AccessibilityNodeInfo): Boolean {
        val inputType = runCatching { node.inputType }.getOrDefault(0)
        if (inputType > 0) {
            val variation = inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
            if (
                variation and InputType.TYPE_TEXT_VARIATION_PASSWORD == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation and InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation and InputType.TYPE_NUMBER_VARIATION_PASSWORD == InputType.TYPE_NUMBER_VARIATION_PASSWORD ||
                variation and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            ) {
                return true
            }
        }
        runCatching {
            val m = node.javaClass.getMethod("isPassword")
            if (m.invoke(node) == true) return true
        }
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("Security", ignoreCase = true) ||
            cls.contains("SafeKeyboard", ignoreCase = true) ||
            cls.contains("Password", ignoreCase = true)) {
            return true
        }
        return false
    }

    /** 往 EditText 写入文本：尝试 ACTION_SET_TEXT，失败回退剪贴板粘贴 */
    private fun appendTextToNode(node: AccessibilityNodeInfo, newText: String): Boolean {
        val args = bundleOf(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE to newText
        )
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) return true

        Log.w(TAG, "ACTION_SET_TEXT 失败，尝试剪贴板粘贴")
        return try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("nya", newText))
            // 全选原文本再粘贴
            val text = node.text?.toString().orEmpty()
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                bundleOf(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT to 0,
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT to text.length
                )
            )
            mainHandler.postDelayed({
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
            }, 15)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "剪贴板方案失败", t)
            false
        }
    }

    companion object {
        @Volatile private var instance: NyaAccessibilityService? = null
        fun isRunning(): Boolean = instance != null
    }
}
