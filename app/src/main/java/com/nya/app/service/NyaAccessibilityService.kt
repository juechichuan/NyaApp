package com.nya.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
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
    @Volatile private var appendMode = AppendMode.IDLE

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
        appendMode = snap.appendMode

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
            // 非全局模式：仅在白名单 App 中生效
            val whitelist = (application as NyaApplication).prefs.snapshotBlocking().whitelistPackages
            if (pkg !in whitelist) {
                Log.d(TAG, "非全局模式且 $pkg 不在白名单，跳过")
                return
            }
        }

        val isWechat = pkg == "com.tencent.mm"

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (!handleTextChanged(event) && isWechat) {
                    // 微信兜底：标准路径失败（如自定义 View 不触发 isEditable）
                    // 直接找窗口里任意含可写文本的节点，不管是不是 EditText
                    Log.i(TAG, "微信：标准 TEXT_CHANGED 未命中，走兜底查找")
                    findAndHandleWechatEditor()
                }
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> maybeCacheFocusedEditor(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 切页面，清掉缓存的 EditText 与待追加任务
                cancelPendingAppend()
                lastFocusedEditor = null
                lastAppendedText = null
                if (isWechat) Log.i(TAG, "微信：窗口变化，重置状态")
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 微信/QQ 在某些版本不主动发 TYPE_VIEW_TEXT_CHANGED，
                // 但会发 WINDOW_CONTENT_CHANGED；微信场景下只要内容变化就尝试处理
                val hasTextChange = event.text?.isNotEmpty() == true ||
                                    event.source?.text?.isNotEmpty() == true ||
                                    (event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT != 0)
                if (hasTextChange || isWechat) {
                    if (!handleTextChanged(event) && isWechat) {
                        findAndHandleWechatEditor()
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleButtonClickedAsFallback(event)
        }
    }

    // ============================
    //  追加逻辑：根据 appendMode 分发
    //  返回：true=已处理（不管成功与否，代表至少找到了可编辑节点）；false=没找到任何可编辑节点
    // ============================
    private fun handleTextChanged(event: AccessibilityEvent): Boolean {
        val source = event.source
        val node = source?.takeIf { isEditable(it) }
            ?: source?.takeIf { isWechatEditable(it) }
            ?: findFocusedEditTextMultiWindow()
            ?: lastFocusedEditor
            ?: return false

        if (isPasswordInput(node)) {
            Log.d(TAG, "密码/安全输入框，跳过")
            return true
        }

        when (appendMode) {
            AppendMode.IDLE -> handleIdleAppend(node)
            AppendMode.PUNCTUATION -> handlePunctuationAppend(node)
        }
        return true
    }

    /**
     * 微信兜底：在整个视图树里找一个"看起来像输入框"的节点并处理。
     * 微信聊天界面的输入框有时是自定义 View（非标准 EditText），
     * isEditable 返回 false，所以这里放宽条件。
     */
    private fun findAndHandleWechatEditor() {
        val windows = runCatching { this.windows }.getOrDefault(emptyList())
        var dumpCount = 0
        for (w in windows) {
            val root = w.root ?: continue
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var count = 0
            while (queue.isNotEmpty() && count < 800) {
                count++
                dumpCount++
                val n = queue.removeFirst()
                if (dumpCount <= 20) {
                    // 前20个节点打印调试信息，方便定位微信 UI 结构
                    val cls = n.className?.toString().orEmpty()
                    val txt = safeGetText(n).take(30)
                    val vid = n.viewIdResourceName.orEmpty()
                    Log.v(TAG, "微信遍历[$dumpCount]: cls=$cls viewId=$vid text=$txt focused=${n.isFocused} editable=${n.isEditable}")
                }
                if (isWechatEditable(n) && !isPasswordInput(n)) {
                    val text = safeGetText(n)
                    if (text.isNotEmpty() || n.isFocused) {
                        Log.i(TAG, "微信兜底：找到疑似输入框 className=${n.className} textLen=${text.length} focused=${n.isFocused} viewId=${n.viewIdResourceName}")
                        when (appendMode) {
                            AppendMode.IDLE -> handleIdleAppend(n)
                            AppendMode.PUNCTUATION -> handlePunctuationAppend(n)
                        }
                        return
                    }
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { queue.add(it) }
                }
            }
        }
    }

    // ============================
    //  模式 1：停顿追加（用户停顿 1.2s 后追加，继续输入时撤回已追加内容并重新计时）
    // ============================
    private fun handleIdleAppend(node: AccessibilityNodeInfo) {
        val currentText = safeGetText(node)
        if (currentText.isBlank()) {
            lastAppendedText = null
            cancelPendingAppend()
            return
        }

        // 用户继续打字 → 如果上次追加过，先撤回（删除末尾的 appendContent）
        if (lastAppendedText != null && currentText == lastAppendedText) {
            // 文本就是上次追加后的版本，用户没继续打字 → 不动
            return
        }
        // 如果当前文本以 appendContent 结尾且长度大于 appendContent（说明追加过且用户没继续输入），
        // 也跳过；若用户在追加后又输入了新字符，currentText 不会等于 lastAppendedText
        cancelPendingAppend()
        if (currentText.endsWith(appendContent) && currentText.length > appendContent.length) {
            // 已经以 appendContent 结尾，但用户可能在追加后继续打字
            // 检查 lastAppendedText 是否匹配 → 不匹配说明用户改了字 → 删掉 appendContent 再重新追加
            if (currentText != lastAppendedText) {
                // 用户在追加后又输入了新字符：撤回 appendContent
                val restored = currentText.removeSuffix(appendContent)
                if (restored != currentText) {
                    Log.d(TAG, "用户在追加后继续输入，撤回「$appendContent」")
                    if (appendTextToNode(node, restored, isWechatPkg(node))) {
                        lastAppendedText = null
                    }
                }
            }
        }

        if (currentText.endsWith(appendContent)) {
            Log.d(TAG, "已经以「$appendContent」结尾，跳过追加")
            return
        }

        val editor = node
        Log.d(TAG, "检测到文本变化，1200ms 后追加「$appendContent」 → 当前: \"$currentText\"")
        val r = Runnable {
            appending = true
            try {
                val latestText = safeGetText(editor)
                if (latestText.isBlank()) {
                    Log.d(TAG, "计时到达，但文本已清空，跳过")
                    return@Runnable
                }
                if (latestText.endsWith(appendContent)) {
                    Log.d(TAG, "计时到达，但末尾已是「$appendContent」，跳过")
                    return@Runnable
                }
                val newText = latestText + appendContent
                val ok = appendTextToNode(editor, newText, isWechatPkg(node))
                if (ok) {
                    lastAppendedText = newText
                    Log.i(TAG, "✓ 已追加「$appendContent」→ \"$newText\"")
                } else {
                    Log.w(TAG, "追加文本失败（所有方案均返回 false）")
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

    // ============================
    //  模式 2：标点符号后追加（末尾是标点时 0.7s 后追加，继续输入则取消）
    // ============================
    private fun handlePunctuationAppend(node: AccessibilityNodeInfo) {
        val currentText = safeGetText(node)
        if (currentText.isBlank()) {
            lastAppendedText = null
            cancelPendingAppend()
            return
        }
        if (currentText == lastAppendedText) return
        if (currentText.endsWith(appendContent)) return

        // 中英文标点集合
        val punctuations = charArrayOf('。', '，', '！', '？', '；', '：', '、',
            '.', ',', '!', '?', ';', ':', '~', '～', '…')
        if (currentText.last() !in punctuations) {
            Log.d(TAG, "标点模式：末尾非标点（${currentText.last()}），跳过")
            cancelPendingAppend()
            return
        }

        // 如果末尾已经是 "标点 + 喵" 的形式（已经追加过），跳过
        if (currentText.length >= 2 && currentText.endsWith(appendContent) &&
            currentText[currentText.length - appendContent.length - 1] in punctuations
        ) return

        // 0.7s 延迟追加（延迟期间用户继续输入则取消）
        cancelPendingAppend()
        val editor = node
        Log.d(TAG, "标点模式：检测到标点，700ms 后追加「$appendContent」 → 当前: \"$currentText\"")
        val r = Runnable {
            appending = true
            try {
                val latestText = safeGetText(editor)
                if (latestText.isBlank()) {
                    Log.d(TAG, "标点模式：计时到达，文本已清空，跳过")
                    return@Runnable
                }
                if (latestText.endsWith(appendContent)) {
                    Log.d(TAG, "标点模式：计时到达，末尾已是「$appendContent」，跳过")
                    return@Runnable
                }
                // 再次确认末尾是标点（用户可能在延迟内又输入了文字）
                val lastChar = latestText.lastOrNull()
                if (lastChar != null && lastChar !in punctuations) {
                    Log.d(TAG, "标点模式：计时到达，但末尾已非标点（$lastChar），跳过")
                    return@Runnable
                }
                val newText = latestText + appendContent
                val ok = appendTextToNode(editor, newText, isWechatPkg(node))
                if (ok) {
                    lastAppendedText = newText
                    Log.i(TAG, "✓ 标点后已追加「$appendContent」→ \"$newText\"")
                } else {
                    Log.w(TAG, "标点追加失败")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "标点追加异常", t)
            } finally {
                mainHandler.postDelayed({ appending = false }, 200)
            }
        }
        pendingAppendRunnable = r
        mainHandler.postDelayed(r, 700)
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
        if (isEditable(node) || isWechatEditable(node)) {
            lastFocusedEditor = node
            Log.i(TAG, "缓存焦点输入框: className=${node.className} editable=${isEditable(node)} wechatEditable=${isWechatEditable(node)} viewId=${node.viewIdResourceName}")
        }
    }

    /**
     * 安全读取节点文本：先 refresh() 再读，
     * 微信自定义 View 常常需要 refresh 才能拿到最新文本，否则会读到旧值或空串。
     */
    private fun safeGetText(node: AccessibilityNodeInfo): String {
        runCatching { node.refresh() }
        val t = node.text?.toString()
        if (!t.isNullOrEmpty()) return t
        // 再兜底：contentDescription 有时包含文本（极少数 App）
        val cd = node.contentDescription?.toString()
        if (!cd.isNullOrEmpty() && cd.length <= 500) return cd
        return ""
    }

    /**
     * 微信自定义 View 识别：不依赖标准 EditText / isEditable 属性
     * 放宽条件：inputType > 0 且 isEnabled isFocusable，或者可点击且可写入文本的容器
     */
    private fun isWechatEditable(node: AccessibilityNodeInfo): Boolean {
        if (isEditable(node)) return true
        val cls = node.className?.toString().orEmpty()
        // 微信已知的自定义输入框类名（不同版本不同，覆盖新老版本）
        if (cls.startsWith("com.tencent.mm.") && cls.contains("edit", ignoreCase = true)) return true
        if (cls.startsWith("com.tencent.mm.") && cls.contains("text", ignoreCase = true) && cls.contains("edit", ignoreCase = true)) return true
        if (cls.startsWith("com.tencent.mm.ui.chatting.") && cls.contains("input", ignoreCase = true)) return true
        if (cls.contains("InputView", ignoreCase = true)) return true
        if (cls.contains("ChattingUIFragment", ignoreCase = true)) return false // 容器，跳过
        if (cls.contains("RecordView", ignoreCase = true)) return false // 语音，跳过
        // ViewId 识别：微信输入框的 viewId 常带 input/editor/chat
        val vid = node.viewIdResourceName.orEmpty()
        if (vid.contains("input", ignoreCase = true) || vid.contains("editor", ignoreCase = true) ||
            vid.contains("chat", ignoreCase = true) && vid.contains("text", ignoreCase = true)) {
            if (node.isEnabled && node.isFocusable) return true
        }
        // 通用兜底：有 inputType (TYPE_CLASS_TEXT) 且 enabled/focusable
        val inputType = runCatching { node.inputType }.getOrDefault(0)
        if (inputType > 0) {
            val textMask = InputType.TYPE_MASK_CLASS
            val isTextLike = (inputType and textMask) == InputType.TYPE_CLASS_TEXT
            if (isTextLike && node.isEnabled && node.isFocusable) return true
        }
        // 兜底：可点击 + 可选中 + 有文本（微信有时把输入框伪装成普通容器）
        if (node.isClickable && node.isFocusable && node.isEnabled && node.isVisibleToUser &&
            (safeGetText(node).isNotEmpty() || node.isFocused)) return true
        return false
    }

    /**
     * 遍历所有可访问窗口查找输入框。
     * ColorOS 15 / 多窗口场景下 rootInActiveWindow 可能返回错误根节点，
     * 必须遍历 windows 列表，找到真正含 EditText 的窗口。
     */
    private fun findFocusedEditTextMultiWindow(): AccessibilityNodeInfo? {
        // 1) 优先用系统焦点 API
        runCatching {
            val focused = this.findFocus(1) // FOCUS_INPUT
            if (focused != null && (isEditable(focused) || isWechatEditable(focused))) return focused
        }
        // 2) 遍历所有 windows，找包含焦点 EditText 的窗口
        runCatching {
            val windows = this.windows // API 21+
            for (w in windows) {
                val root = w.root ?: continue
                // 找该窗口下 isFocused=true 的 EditText
                val found = findFocusedEditText(root)
                if (found != null) {
                    Log.d(TAG, "在 window(${w.type}) 中找到焦点输入框")
                    return found
                }
            }
        }
        // 3) 兜底：rootInActiveWindow
        return findFocusedEditText(rootInActiveWindow)
    }

    /** 在单个 root 下查找焦点输入框（优先 isFocused，否则任意 editable / 微信自定义） */
    private fun findFocusedEditText(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var firstEditable: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (isEditable(n) || isWechatEditable(n)) {
                if (n.isFocused) return n
                if (firstEditable == null) firstEditable = n
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                queue.add(c)
            }
        }
        return firstEditable
    }

    // ============================
    //  兜底：发送按钮点击（少数 App 在 onClick 里二次读取文本，能再追一次）
    // ============================
    private fun handleButtonClickedAsFallback(event: AccessibilityEvent) {
        if (appending) return
        val clickedNode = event.source ?: return
        if (!isSendLikeButton(clickedNode)) return

        val editor = findFocusedEditTextMultiWindow()
            ?: lastFocusedEditor
            ?: run {
                Log.d(TAG, "fallback: 没有找到输入框")
                return
            }
        if (isPasswordInput(editor)) return

        val currentText = safeGetText(editor)
        if (currentText.isBlank() || currentText.endsWith(appendContent)) return

        val isWechat = isWechatPkg(editor) || event.packageName?.toString() == "com.tencent.mm"
        if (isWechat) Log.i(TAG, "微信 fallback：点击发送按钮，尝试前置追加")

        appending = true
        try {
            val newText = currentText + appendContent
            appendTextToNode(editor, newText, isWechat)
            lastAppendedText = newText
            Log.i(TAG, "✓ fallback 已追加「$appendContent」→ \"$newText\"")
        } catch (t: Throwable) {
            Log.e(TAG, "fallback 追加异常", t)
        } finally {
            // 微信场景：延长 appending 时间，确保写入完成后再放行
            mainHandler.postDelayed({ appending = false }, if (isWechat) 500 else 300)
        }
    }

    private fun isWechatPkg(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.packageName?.toString() }.getOrNull() == "com.tencent.mm"

    // ============================
    //  辅助方法
    // ============================

    /** 是否是"发送/确定/下一步/Send/Done"类按钮 */
    private fun isSendLikeButton(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) return false
        val text = (node.text?.toString() ?: "").trim()
        val desc = (node.contentDescription?.toString() ?: "").trim()
        val keywords = listOf(
            "发送", "确定", "完成", "搜索", "下一步", "前往", "发送消息", "发送消息按钮", "发送按钮",
            "Send", "Send message", "Done", "Search", "Next", "Go", "Submit", "Enter", "Post",
            "评论", "发布", "回复", "提交", "Confirm", "OK", "传送", "纸飞机", "箭头",
            "更多", "表情", "语音", "图片"
        )
        // 注意：排除"更多/表情/语音/图片"按钮（含"更多"也不算发送）
        val sendKeywords = keywords.filter { it !in listOf("更多", "表情", "语音", "图片") }
        if (sendKeywords.any { text.equals(it, ignoreCase = true) }) return true
        if (sendKeywords.any { desc.equals(it, ignoreCase = true) }) return true

        // 子孙节点文案匹配
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val cText = (c.text?.toString() ?: "").trim()
            val cDesc = (c.contentDescription?.toString() ?: "").trim()
            if (sendKeywords.any { it.equals(cText, ignoreCase = true) || it.equals(cDesc, ignoreCase = true) }) {
                return true
            }
        }

        // 微信/钉钉/QQ 的图标发送按钮（contentDescription 可能是"发送按钮"+"发送"或带版本号）
        // 匹配策略：desc 包含"发送" 即可
        if (desc.contains("发送") || text.contains("发送")) return true
        return false
    }

    private fun isEditable(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("EditText") || cls.contains("TextInputEdit") || cls.contains("TextField")) return true
        if (cls == "android.widget.EditText") return true
        if (cls == "androidx.appcompat.widget.AppCompatEditText") return true
        // 微信 / 企业微信 / 腾讯系 App 的自定义可编辑控件
        if (cls.contains("tencent") && cls.contains("Edit")) return true
        if (cls.contains("WX") && cls.contains("Edit")) return true
        // 退化判断：有 text 且可聚焦，inputType 是文本类
        val inputType = runCatching { node.inputType }.getOrDefault(0)
        if (inputType > 0 && (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
            if (node.isFocusable) return true
        }
        return false
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

    /**
     * 往输入框写入文本：四层 fallback（** 微信场景优先剪贴板 **）
     *
     * 顺序（微信 isWechat=true 时调换 1、2 优先级）：
     *   1. ACTION_SET_TEXT（标准 App 首选）
     *   2. 全选 + 剪贴板 ACTION_PASTE（微信首选，绝大多数版本都能过）
     *   3. 光标移到末尾：ACTION_SET_SELECTION(end) + 剪贴板 PASTE（追加写入，避免全选闪烁）
     *   4. dispatchGesture 长按节点，触发系统"粘贴"弹窗（终极兜底，绕过所有 API 拦截）
     *
     * 最终都失败返回 false。
     */
    private fun appendTextToNode(node: AccessibilityNodeInfo, newText: String, isWechat: Boolean): Boolean {
        // 步骤 0：先 focus（微信等 App 要求焦点后才接受写入）
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        runCatching { node.refresh() }

        // 组装方案顺序
        val strategies: List<Pair<String, () -> Boolean>> = if (isWechat) {
            // 微信：优先剪贴板，再试 SET_TEXT
            listOf(
                "剪贴板_全选粘贴" to { tryClipboardSelectAllPaste(node, newText) },
                "ACTION_SET_TEXT" to { tryActionSetText(node, newText) },
                "剪贴板_光标后追加" to { tryClipboardAppendAtEnd(node, newText) },
                "手势_长按粘贴" to { tryGestureLongPressPaste(node, newText) }
            )
        } else {
            // 普通 App：标准流程
            listOf(
                "ACTION_SET_TEXT" to { tryActionSetText(node, newText) },
                "剪贴板_全选粘贴" to { tryClipboardSelectAllPaste(node, newText) },
                "剪贴板_光标后追加" to { tryClipboardAppendAtEnd(node, newText) },
                "手势_长按粘贴" to { tryGestureLongPressPaste(node, newText) }
            )
        }

        for ((name, block) in strategies) {
            try {
                if (block()) {
                    Log.i(TAG, "写入成功: 策略=$name")
                    // 验证：120ms 后重读文本核对是否真的写入成功
                    mainHandler.postDelayed({
                        runCatching {
                            val verified = safeGetText(node)
                            if (verified == newText) {
                                Log.i(TAG, "写入验证通过 ✓ finalText=$verified")
                            } else {
                                Log.w(TAG, "写入验证失败 ✗ 期望=\"$newText\" 实际=\"$verified\"")
                            }
                        }
                    }, 180)
                    return true
                } else {
                    Log.d(TAG, "策略 $name 返回 false，尝试下一策略")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "策略 $name 异常", t)
            }
        }
        return false
    }

    // —— 写入策略 1：ACTION_SET_TEXT ——
    private fun tryActionSetText(node: AccessibilityNodeInfo, newText: String): Boolean {
        val args = bundleOf(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE to newText)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) return false
        // 返回 true 不代表真写入，微信常假阳性；调用方会异步验证
        return true
    }

    // —— 写入策略 2：剪贴板 + 全选 + PASTE ——
    private fun tryClipboardSelectAllPaste(node: AccessibilityNodeInfo, newText: String): Boolean {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("nya", newText))

        // 全选现有文本再粘贴，等价于覆盖写入
        val curLen = safeGetText(node).length
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            bundleOf(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT to 0,
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT to curLen
            )
        )
        // 稍等再粘贴
        Thread.sleep(30)
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (!pasted) {
            // 再试：取消选择 → 光标放到末尾 → 再次粘贴
            Thread.sleep(20)
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                bundleOf(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT to curLen,
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT to curLen
                )
            )
            return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        return true
    }

    // —— 写入策略 3：剪贴板 + 光标放末尾 + PASTE（追加，非覆盖）——
    private fun tryClipboardAppendAtEnd(node: AccessibilityNodeInfo, newText: String): Boolean {
        val cur = safeGetText(node)
        // 只追加差异部分（减少粘贴内容）
        val delta = if (newText.startsWith(cur) && newText.length > cur.length) {
            newText.substring(cur.length)
        } else newText

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("nya", delta))

        // 光标放末尾
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            bundleOf(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT to cur.length,
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT to cur.length
            )
        )
        Thread.sleep(25)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    // —— 写入策略 4：dispatchGesture 长按节点，触发系统粘贴菜单 ——
    // 当所有无障碍 API 都被 App 拦截时，用手势模拟手指长按，几乎 100% 能弹出"粘贴"菜单
    private fun tryGestureLongPressPaste(node: AccessibilityNodeInfo, newText: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        // 把内容先放剪贴板
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("nya", newText))

        val rect = android.graphics.Rect()
        runCatching { node.getBoundsInScreen(rect) }.onFailure { return false }
        if (rect.isEmpty()) return false

        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()
        Log.i(TAG, "手势兜底：长按坐标 ($cx, $cy) 触发粘贴")

        // 方案 A：长按输入框，让弹出菜单出现，系统菜单的"粘贴"由用户点击？
        // 不行，必须全自动。改为：长按 + 然后在"粘贴"按钮的预期位置再点一下
        //
        // 由于"粘贴"菜单位置不确定，改为更可靠的方案：
        //   1) 长按输入框弹出菜单
        //   2) 菜单中通常第一个选项就是"粘贴"/"全选"，这里改用更稳妥的方式：
        //      通过 gesture 长按 500ms 释放，菜单弹出概率最高
        //   3) 400ms 后再遍历当前窗口，找"粘贴"文案的按钮并点击

        // 长按轨迹（Path：单指压下→停留 500ms→抬起）
        val pressPath = Path().apply { moveTo(cx, cy) }
        val longPressStroke = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.accessibilityservice.GestureDescription.StrokeDescription(
                pressPath, 0, 600, true
            )
        } else {
            android.accessibilityservice.GestureDescription.StrokeDescription(
                pressPath, 0, 600
            )
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(longPressStroke)
            .build()

        var dispatched = false
        dispatched = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.i(TAG, "长按手势完成")
                // 延迟 550ms 后在窗口里找"粘贴"按钮点击
                mainHandler.postDelayed({
                    runCatching {
                        clickPasteMenuItemInWindows()
                    }
                }, 550)
            }
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.w(TAG, "长按手势被取消")
            }
        }, null)

        if (!dispatched) {
            Log.w(TAG, "dispatchGesture 返回 false，手势无法下发")
            return false
        }
        // 这里无法同步知道结果，返回 true 让调用方认为已处理（会有异步验证日志）
        return true
    }

    /** 在当前所有窗口中查找文案/描述含"粘贴"的节点并点击 */
    private fun clickPasteMenuItemInWindows() {
        val windows = runCatching { this.windows }.getOrDefault(emptyList())
        for (w in windows) {
            val root = w.root ?: continue
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var count = 0
            while (queue.isNotEmpty() && count < 400) {
                count++
                val n = queue.removeFirst()
                val t = n.text?.toString().orEmpty()
                val d = n.contentDescription?.toString().orEmpty()
                if (n.isVisibleToUser && n.isClickable && (t == "粘贴" || d == "粘贴" ||
                            t.equals("Paste", ignoreCase = true) || d.equals("Paste", ignoreCase = true) ||
                            t.contains("粘贴") || d.contains("粘贴"))) {
                    Log.i(TAG, "找到粘贴按钮，点击: text=$t desc=$d")
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { queue.add(it) }
                }
            }
        }
        Log.w(TAG, "未找到粘贴菜单项，可能菜单未弹出或菜单位置异常")
    }

    companion object {
        @Volatile private var instance: NyaAccessibilityService? = null
        fun isRunning(): Boolean = instance != null
    }
}
