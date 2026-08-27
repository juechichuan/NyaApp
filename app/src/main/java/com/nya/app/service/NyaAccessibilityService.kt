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
import com.nya.app.data.AppendMode
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
 */
class NyaAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var masterEnabled = true
    @Volatile private var appendContent = "喵"
    @Volatile private var isGlobalMode = true
    @Volatile private var appendMode = AppendMode.IDLE
    @Volatile private var idleDelayMs = 1200
    @Volatile private var punctuationDelayMs = 700

    @Volatile private var pendingAppendRunnable: Runnable? = null
    @Volatile private var lastAppendedText: String? = null
    @Volatile private var appending = false

    @Volatile private var lastFocusedEditor: AccessibilityNodeInfo? = null

    /** 每个节点的"上次已知文本长度"，用于识别用户是"增加字符"还是"删除字符"。
     *  键 = node.hashCode()；切换窗口/焦点变化时清空。 */
    private val textLengthByNode = hashMapOf<Int, Int>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        val snap = (application as NyaApplication).prefs.snapshotBlocking()
        masterEnabled = snap.masterEnabled
        appendContent = snap.appendContent.ifBlank { "喵" }
        isGlobalMode = snap.isGlobalMode
        appendMode = snap.appendMode
        idleDelayMs = snap.idleDelayMs
        punctuationDelayMs = snap.punctuationDelayMs

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
        scope.launch {
            (application as NyaApplication).prefs.appendMode.collectLatest {
                withContext(Dispatchers.Main) { appendMode = it }
            }
        }
        scope.launch {
            (application as NyaApplication).prefs.idleDelayMs.collectLatest {
                withContext(Dispatchers.Main) { idleDelayMs = it }
            }
        }
        scope.launch {
            (application as NyaApplication).prefs.punctuationDelayMs.collectLatest {
                withContext(Dispatchers.Main) { punctuationDelayMs = it }
            }
        }
    }

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
                        AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
                notificationTimeout = 20L
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
            val whitelist = (application as NyaApplication).prefs.snapshotBlocking().whitelistPackages
            if (pkg !in whitelist) return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                textLengthByNode.clear()
                maybeCacheFocusedEditor(event)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                textLengthByNode.clear()
                cancelPendingAppend()
                lastFocusedEditor = null
                lastAppendedText = null
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val hasTextChange = event.text?.isNotEmpty() == true ||
                                    event.source?.text?.isNotEmpty() == true ||
                                    (event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT != 0)
                if (hasTextChange) handleTextChanged(event)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleButtonClickedAsFallback(event)
        }
    }

    // ============================
    //  核心：文本变化入口（新增删除字符过滤 + 空文本兜底）
    // ============================
    private fun handleTextChanged(event: AccessibilityEvent) {
        val source = event.source
        val node = source?.takeIf { isEditable(it) }
            ?: findFocusedEditTextMultiWindow()
            ?: lastFocusedEditor
            ?: return

        if (isPasswordInput(node)) return

        val currentText = node.text?.toString().orEmpty()
        val currentLen = currentText.length
        val nodeKey = node.hashCode()
        val previousLen = textLengthByNode[nodeKey]

        // ---- 1. 空文本：绝对不触发，清除所有状态
        if (currentLen == 0) {
            textLengthByNode[nodeKey] = 0
            lastAppendedText = null
            cancelPendingAppend()
            return
        }

        // ---- 2. 删除字符（长度变短）：不触发；仅更新长度，取消任何已排队任务
        if (previousLen != null && currentLen < previousLen) {
            textLengthByNode[nodeKey] = currentLen
            cancelPendingAppend()
            // 用户在删除 → 我们之前追加的"喵"不再作为锚点，清空 lastAppendedText，
            // 防止用户刚删完"喵"下一次 handle 因为 currentText==lastAppendedText 而误判
            lastAppendedText = null
            return
        }

        // ---- 3. 无变化（可能是重发事件）：长度一致且文本相等 → 不重复排程
        if (previousLen != null && currentLen == previousLen && currentText == lastAppendedText) {
            return
        }

        // ---- 4. 记录本次长度，交给两种追加模式
        textLengthByNode[nodeKey] = currentLen

        when (appendMode) {
            AppendMode.IDLE -> handleIdleAppend(node, currentText)
            AppendMode.PUNCTUATION -> handlePunctuationAppend(node, currentText)
        }
    }

    // ============================
    //  模式 1：停顿追加
    // ============================
    private fun handleIdleAppend(node: AccessibilityNodeInfo, currentText: String) {
        if (currentText.isBlank()) {
            lastAppendedText = null
            cancelPendingAppend()
            return
        }

        if (lastAppendedText != null && currentText == lastAppendedText) return

        cancelPendingAppend()
        if (currentText.endsWith(appendContent) && currentText.length > appendContent.length) {
            if (currentText != lastAppendedText) {
                val restored = currentText.removeSuffix(appendContent)
                if (restored != currentText) {
                    if (appendTextToNode(node, restored)) {
                        lastAppendedText = null
                    }
                }
            }
        }

        if (currentText.endsWith(appendContent)) return

        val editor = node
        // 快照：仅当用户在 idleDelayMs 内既没新增也没删除时才执行追加
        val expectedSnapshot = currentText
        Log.d(TAG, "检测到文本变化，${idleDelayMs}ms 后追加「$appendContent」 → 当前: \"$currentText\"")
        val r = Runnable {
            appending = true
            try {
                val latestText = editor.text?.toString().orEmpty()
                // --- 空文本绝对不追加
                if (latestText.isBlank()) return@Runnable
                // --- 用户在停顿期间有新增/删除/替换 → 本次排程作废
                if (latestText != expectedSnapshot) return@Runnable
                // --- 末尾已经有追加内容 → 跳过（防止重复）
                if (latestText.endsWith(appendContent)) return@Runnable
                val newText = latestText + appendContent
                val ok = appendTextToNode(editor, newText)
                if (ok) {
                    lastAppendedText = newText
                    // 更新长度记录，防止下一次 handle 误判为"新增字符"
                    textLengthByNode[editor.hashCode()] = newText.length
                    Log.i(TAG, "✓ 已追加「$appendContent」→ \"$newText\"")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "追加文本异常", t)
            } finally {
                mainHandler.postDelayed({ appending = false }, 200)
            }
        }
        pendingAppendRunnable = r
        mainHandler.postDelayed(r, idleDelayMs.toLong())
    }

    // ============================
    //  模式 2：标点符号后追加
    // ============================
    private fun handlePunctuationAppend(node: AccessibilityNodeInfo, currentText: String) {
        if (currentText.isBlank()) {
            lastAppendedText = null
            cancelPendingAppend()
            return
        }
        if (currentText == lastAppendedText) return
        if (currentText.endsWith(appendContent)) return

        val punctuations = charArrayOf('。', '，', '！', '？', '；', '：', '、',
            '.', ',', '!', '?', ';', ':', '~', '～', '…')
        if (currentText.last() !in punctuations) {
            cancelPendingAppend()
            return
        }

        if (currentText.length >= 2 && currentText.endsWith(appendContent) &&
            currentText[currentText.length - appendContent.length - 1] in punctuations
        ) return

        cancelPendingAppend()
        val editor = node
        val expectedSnapshot = currentText
        Log.d(TAG, "标点模式：${punctuationDelayMs}ms 后追加「$appendContent」 → 当前: \"$currentText\"")
        val r = Runnable {
            appending = true
            try {
                val latestText = editor.text?.toString().orEmpty()
                // --- 空文本绝对不追加
                if (latestText.isBlank()) return@Runnable
                // --- 停顿期间文本被改动（增/删/换） → 排程作废
                if (latestText != expectedSnapshot) return@Runnable
                if (latestText.endsWith(appendContent)) return@Runnable
                val lastChar = latestText.lastOrNull()
                if (lastChar != null && lastChar !in punctuations) return@Runnable
                val newText = latestText + appendContent
                val ok = appendTextToNode(editor, newText)
                if (ok) {
                    lastAppendedText = newText
                    textLengthByNode[editor.hashCode()] = newText.length
                    Log.i(TAG, "✓ 标点后已追加「$appendContent」→ \"$newText\"")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "标点追加异常", t)
            } finally {
                mainHandler.postDelayed({ appending = false }, 200)
            }
        }
        pendingAppendRunnable = r
        mainHandler.postDelayed(r, punctuationDelayMs.toLong())
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
        }
    }

    /**
     * 遍历所有可访问窗口查找输入框。
     */
    private fun findFocusedEditTextMultiWindow(): AccessibilityNodeInfo? {
        runCatching {
            val focused = this.findFocus(1) // FOCUS_INPUT
            if (focused != null && isEditable(focused)) return focused
        }
        runCatching {
            val windows = this.windows
            for (w in windows) {
                val root = w.root ?: continue
                val found = findFocusedEditText(root)
                if (found != null) return found
            }
        }
        return findFocusedEditText(rootInActiveWindow)
    }

    private fun findFocusedEditText(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var firstEditable: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (isEditable(n)) {
                if (n.isFocused) return n
                if (firstEditable == null) firstEditable = n
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return firstEditable
    }

    // ============================
    //  兜底：发送按钮点击
    // ============================
    private fun handleButtonClickedAsFallback(event: AccessibilityEvent) {
        if (appending) return
        val clickedNode = event.source ?: return
        if (!isSendLikeButton(clickedNode)) return

        val editor = findFocusedEditTextMultiWindow()
            ?: lastFocusedEditor
            ?: return
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

    private fun isSendLikeButton(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) return false
        val text = (node.text?.toString() ?: "").trim()
        val desc = (node.contentDescription?.toString() ?: "").trim()
        val keywords = listOf(
            "发送", "确定", "完成", "搜索", "下一步", "前往",
            "Send", "Done", "Search", "Next", "Go", "Submit", "Enter", "Post",
            "评论", "发布", "回复", "提交", "Confirm", "OK"
        )
        if (keywords.any { text.equals(it, ignoreCase = true) }) return true
        if (keywords.any { desc.equals(it, ignoreCase = true) }) return true
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val cText = (c.text?.toString() ?: "").trim()
            val cDesc = (c.contentDescription?.toString() ?: "").trim()
            if (keywords.any { it.equals(cText, ignoreCase = true) || it.equals(cDesc, ignoreCase = true) }) {
                return true
            }
        }
        if (desc.contains("发送") || text.contains("发送")) return true
        return false
    }

    private fun isEditable(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("EditText") || cls.contains("TextInputEdit") || cls.contains("TextField")) return true
        val inputType = runCatching { node.inputType }.getOrDefault(0)
        if (inputType > 0 && (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
            if (node.isFocusable) return true
        }
        return false
    }

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
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("Security", ignoreCase = true) ||
            cls.contains("SafeKeyboard", ignoreCase = true) ||
            cls.contains("Password", ignoreCase = true)) {
            return true
        }
        return false
    }

    /**
     * 往输入框写入文本：两层 fallback
     *  1. ACTION_SET_TEXT（标准 EditText）
     *  2. 全选 + 剪贴板 ACTION_PASTE（兼容拦截 setText 的输入框）
     */
    private fun appendTextToNode(node: AccessibilityNodeInfo, newText: String): Boolean {
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }

        val args = bundleOf(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE to newText
        )
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) return true

        // 剪贴板 + ACTION_PASTE 兜底
        return try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("nya", newText))
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
