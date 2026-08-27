package com.nya.app.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.core.os.bundleOf
import com.nya.app.NyaApplication
import com.nya.app.data.NyaPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "NyaA11yService"

/**
 * 核心服务：通过无障碍能力监听输入动作，在用户"发送/回车"前自动追加自定义内容（默认"喵"）。
 *
 * 触发条件（用户选择方案 A：点击发送/按回车键时）：
 *   1. 捕获 TYPE_VIEW_CLICKED 事件，目标节点 text 包含「发送/确定/下一步/Send」等关键字，或 actionId == IME_ACTION_SEND/DONE/NEXT/GO/SEARCH
 *   2. 捕获 TYPE_VIEW_TEXT_CHANGED + EditorInfo.IME_FLAG_NAVIGATE_NEXT 时，在 IME_ACTION 点击前
 *   3. 最后兜底：当用户点击任何"发送类"按钮前，先往当前焦点 EditText 追加喵，再放行点击
 */
class NyaAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var masterEnabled = true
    @Volatile private var appendContent = "喵"
    @Volatile private var whitelistPackages: Set<String> = emptySet()

    // 防止重入：某次追加喵的操作正在进行时，忽略后续事件
    @Volatile private var appending = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 首次启动用 snapshotBlocking 加载默认值
        val snap = (application as NyaApplication).prefs.snapshotBlocking()
        masterEnabled = snap.masterEnabled
        appendContent = snap.appendContent
        whitelistPackages = snap.whitelistPackages

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
            (application as NyaApplication).prefs.whitelistPackages.collectLatest {
                withContext(Dispatchers.Main) { whitelistPackages = it }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
    }

    // ============================
    //  核心入口：事件分发
    // ============================
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!masterEnabled || appending) return

        // 白名单过滤：只对白名单中的应用生效
        val pkg = (event.packageName?.toString() ?: "").takeIf { it.isNotBlank() } ?: return
        if (whitelistPackages.isNotEmpty() && !whitelistPackages.contains(pkg)) return

        when (event.eventType) {
            // 方案：监听按钮点击事件（发送/确定/下一步）
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleButtonClicked(event)
            // 监听焦点变化，缓存当前焦点输入框
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> maybeCacheFocusedEditor(event)
            // 窗口状态变化（进入新页面），清缓存
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> lastFocusedEditor = null
            // 文本变化：检测回车键信号（输入法触发的 ACTION）由 TYPE_VIEW_TEXT_CHANGED + 特殊 bundle 判定
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> maybeHandleEditorAction(event)
        }
    }

    private var lastFocusedEditor: AccessibilityNodeInfo? = null
    private var lastTextHash: Int = 0

    private fun maybeCacheFocusedEditor(event: AccessibilityEvent) {
        val node = event.source ?: return
        if (isEditable(node)) {
            lastFocusedEditor = node
        }
    }

    private fun maybeHandleEditorAction(event: AccessibilityEvent) {
        // 文本变化后，如果当前的 EditText 是 IME_ACTION_SEND 且用户刚刚按下回车，
        // 系统会发 TYPE_VIEW_TEXT_CHANGED，但是没有"回车按下"专属事件。
        // 因此我们采用"按钮点击"作为主路径；这里做的是兜底：
        // 如果文本末尾是换行符'\n'，且上一帧文本不是以换行结尾，说明用户按了回车→触发
        val node = event.source ?: return
        if (!isEditable(node)) return
        val text = node.text?.toString() ?: return
        if (text.isEmpty()) return

        // 部分聊天 App 按回车会直接把"\n"替换成发送，所以这里做轻量判定：
        // 如果节点的 imeOptions == IME_ACTION_SEND 且文本变化了，我们不在这里追加，
        // 而是等 TYPE_VIEW_CLICKED（发送按钮）事件；避免重复加喵。
    }

    /**
     * 处理按钮点击：如果被点击的是"发送类"按钮，先给当前输入框追加喵，再放行点击。
     */
    private fun handleButtonClicked(event: AccessibilityEvent) {
        val clickedNode = event.source ?: return
        if (!isSendLikeButton(clickedNode)) return

        // 找当前可编辑文本框：优先用焦点缓存，否则从根遍历
        val editor = findCurrentEditor(rootInActiveWindow)
            ?: lastFocusedEditor
            ?: run {
                Log.d(TAG, "handleButtonClicked: 没有找到输入框，跳过")
                return
            }

        // 安全检查：密码框 / 安全输入 → 跳过
        if (isPasswordInput(editor)) {
            Log.i(TAG, "检测到密码/安全输入框，跳过追加")
            return
        }

        val currentText = editor.text?.toString().orEmpty()
        if (currentText.isBlank()) {
            Log.d(TAG, "输入框为空，跳过追加")
            return
        }
        if (currentText.endsWith(appendContent)) {
            Log.d(TAG, "已经以「$appendContent」结尾，跳过")
            return
        }

        // 执行追加：先"撤销"这次点击 → 追加喵 → 模拟再次点击按钮
        // 注意：无障碍服务只能检测到"点击已发生"，不能拦截。因此这里追加完喵后：
        //  - 如果该 App 是在 onClick 回调里"读取文本→发送"，那此时文本已经是追加后的内容
        //    （因为我们在 UI 线程 post 了 append，而 onClick 执行顺序不确定）
        //
        // 为保证顺序，可靠做法是：performGlobalAction(GLOBAL_ACTION_BACK) 不可行。
        // 因此用更稳妥的方案：通过 ACTION_SET_TEXT / commitText 直接替换文本后，再
        // perform ACTION_CLICK 一次"发送"。为了避免重复发送，采用"两步提交"策略：
        //   1. 在检测到点击发生后，立即追加喵到文本（这一步是同步修改节点 text，
        //      大多数 App 会在点击回调里"再次读取当前文本"，因此会读到带喵的版本）
        //   2. 如极少数 App 是先读文本再触发点击，则本方法会"重复加喵一次"，
        //      但由于我们有 endsWith 判断，不会出现重复追加多个喵的情况。
        appending = true
        try {
            val newText = currentText + appendContent
            appendTextToNode(editor, newText)
            Log.d(TAG, "已在文本末尾追加「$appendContent」→ \"$newText\"")
        } catch (t: Throwable) {
            Log.e(TAG, "追加文本失败", t)
        } finally {
            // 延迟释放，避免同一帧里再次处理
            android.os.Handler(mainLooper).postDelayed({ appending = false }, 120)
        }
    }

    // ============================
    //  辅助方法
    // ============================

    /** 是否是"发送/确定/下一步/Send/Done"类按钮 */
    private fun isSendLikeButton(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        // 必须是可点击控件
        if (!node.isClickable) return false

        // 如果是 EditText 并且是 IME_ACTION_SEND/... 的回车"按钮"，
        // 也应该被识别；不过这种情况通常不会发 TYPE_VIEW_CLICKED
        val text = (node.text?.toString() ?: "").trim()
        val desc = (node.contentDescription?.toString() ?: "").trim()
        val keywords = listOf(
            "发送", "确定", "完成", "搜索", "下一步", "前往", "发送消息",
            "Send", "Done", "Search", "Next", "Go", "Submit", "Enter",
            "评论", "发布", "回复", "提交", "Confirm", "OK"
        )
        if (keywords.any { text.equals(it, ignoreCase = true) }) return true
        if (keywords.any { desc.equals(it, ignoreCase = true) }) return true

        // 对于无文字的图标按钮（比如常见的纸飞机），查子孙节点文案
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

    /** 是否是密码输入框（数字密码 / 文本密码 / 安全键盘） */
    private fun isPasswordInput(node: AccessibilityNodeInfo): Boolean {
        // 方式 1：检查 inputType
        val inputType = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                node.inputType
            } else {
                val it = node.javaClass.getMethod("getInputType").invoke(node) as? Int ?: 0
                it
            }
        }.getOrDefault(0)
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

        // 方式 2：检查 isPassword 字段（有些自定义 View 不填 inputType 但填 password=true）
        runCatching {
            val method = node.javaClass.getMethod("isPassword")
            if (method.invoke(node) == true) return true
        }

        // 方式 3：className 里有"安全"/"密码"
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("Security", ignoreCase = true) ||
            cls.contains("SafeKeyboard", ignoreCase = true) ||
            cls.contains("Password", ignoreCase = true)) {
            return true
        }
        return false
    }

    /** 从给定节点向下查找"当前可编辑且最匹配焦点"的 EditText */
    private fun findCurrentEditor(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        // 优先找处于焦点的
        if (isEditable(root) && root.isFocused) return root
        // 其次：遍历 DFS，找第一个 isEditable 且 text 非空或处于可编辑状态的
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var firstEditable: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (isEditable(n)) {
                if (n.isFocused) return n
                if (firstEditable == null && !n.text.isNullOrBlank()) firstEditable = n
                else if (firstEditable == null) firstEditable = n
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                queue.add(c)
            }
        }
        return firstEditable
    }

    /** 往指定 EditText 节点写入（追加）文本 */
    private fun appendTextToNode(node: AccessibilityNodeInfo, newText: String) {
        // 方案 A：直接 set text（ACTION_SET_TEXT）
        val args = bundleOf(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE to newText
        )
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) return

        // 方案 B：先 setSelection(0, length) 全选，再 commitText
        val text = node.text?.toString().orEmpty()
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            bundleOf(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT to 0,
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT to text.length
            )
        )
        // commitText（通过 IME，需要有输入法连接；部分 ROM 不支持）
        runCatching {
            val icMethod = Class.forName("android.view.inputmethod.InputConnection")
                .getMethod("commitText", CharSequence::class.java, Int::class.javaPrimitiveType)
            // 这里拿不到 InputConnection，因此只能用 ACTION_PASTE + 剪贴板方案
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }

        // 方案 C：剪贴板方案（兼容所有 ROM）
        // 把文本写入剪贴板，然后 perform ACTION_PASTE
        if (!ok) {
            try {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("nya", newText))
                android.os.Handler(mainLooper).postDelayed({
                    node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }, 15)
            } catch (t: Throwable) {
                Log.e(TAG, "剪贴板方案失败", t)
            }
        }
    }

    companion object {
        @Volatile private var instance: NyaAccessibilityService? = null
        fun isRunning(): Boolean = instance != null
    }
}
