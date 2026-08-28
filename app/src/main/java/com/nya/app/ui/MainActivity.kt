package com.nya.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nya.app.NyaApplication
import com.nya.app.data.AppendMode
import com.nya.app.data.NyaPrefs
import com.nya.app.service.NyaAccessibilityService
import com.nya.app.service.NyaForegroundService
import com.nya.app.update.DownloadState
import com.nya.app.update.UpdateDecision
import com.nya.app.update.downloadApkWithProgress
import com.nya.app.update.fetchUpdateInfo
import com.nya.app.update.tryInstallApk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var prefs: NyaPrefs

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startForegroundServiceCompat()
        }

    /** 启动下载流程：先检查安装权限，再启动带进度的协程下载 */
    private fun startUpdateDownload(
        decision: UpdateDecision,
        scope: kotlinx.coroutines.CoroutineScope,
        onStateChange: (DownloadState) -> Unit
    ) {
        // 检查安装权限（8.0+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val allowed = try {
                packageManager.canRequestPackageInstalls()
            } catch (e: Exception) { false }
            if (!allowed) {
                try {
                    val i = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                } catch (e: Exception) {
                    // ColorOS 可能不支持此 Intent，降级到通用设置页
                    try {
                        val fallback = Intent(Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(fallback)
                    } catch (e2: Exception) {
                        onStateChange(DownloadState.Failed("无法打开安装权限设置页，请手动到系统设置中开启"))
                    }
                }
                return
            }
        }
        // 启动下载协程
        scope.launch {
            downloadApkWithProgress(this@MainActivity, decision.info) { state ->
                // 确保在主线程更新 Compose state
                runOnUiThread { onStateChange(state) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = (application as NyaApplication).prefs
        ensureForegroundService()
        val localPair = getLocalVersion()
        val localVC = localPair.first
        val localVN = localPair.second
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFFE91E63),
                secondary = Color(0xFFF48FB1),
                tertiary = Color(0xFF7C4DFF)
            )) {
                val ctx = LocalContext.current as MainActivity
                val scope = rememberCoroutineScope()
                var updateDecision by remember { mutableStateOf<UpdateDecision?>(null) }
                var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
                var downloadDone by remember { mutableStateOf(false) }  // 防止下载成功后重复弹窗
                LaunchedEffect(Unit) {
                    val info = withContext(Dispatchers.IO) { fetchUpdateInfo() }
                    if (info != null) updateDecision = info.evaluate(localVC)
                }

                // —— 下载中：进度条 Dialog ——
                when (val ds = downloadState) {
                    is DownloadState.Progress -> {
                        AlertDialog(
                            onDismissRequest = { /* 下载中不可取消 */ },
                            title = { Text("⬇️ 正在下载更新", fontWeight = FontWeight.Bold) },
                            text = {
                                Column {
                                    LinearProgressIndicator(
                                        progress = { ds.percent / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFFE25C8A),
                                        trackColor = Color(0xFFFCE4EC)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "${ds.percent}%  (${formatBytes(ds.downloadedBytes)} / ${formatBytes(ds.totalBytes)})",
                                        fontSize = 12.sp, color = Color.Gray
                                    )
                                }
                            },
                            confirmButton = { },
                            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
                        )
                    }
                    is DownloadState.Verifying -> {
                        AlertDialog(
                            onDismissRequest = { },
                            title = { Text("🔐 正在校验完整性", fontWeight = FontWeight.Bold) },
                            text = {
                                Column {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp),
                                        color = Color(0xFFE25C8A),
                                        strokeWidth = 3.dp
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text("正在计算 MD5 并校验文件...", fontSize = 12.sp, color = Color.Gray)
                                }
                            },
                            confirmButton = { },
                            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
                        )
                    }
                    is DownloadState.Success -> {
                        LaunchedEffect(Unit) {
                            try {
                                tryInstallApk(ctx, ds.apkFile)
                            } catch (e: Exception) {
                                // 安装失败也不重新弹窗，显示错误
                                downloadState = DownloadState.Failed("唤起安装器失败: ${e.message}")
                            }
                            downloadDone = true
                            downloadState = DownloadState.Idle
                        }
                    }
                    is DownloadState.Failed -> {
                        AlertDialog(
                            onDismissRequest = {
                                if (!(updateDecision?.forced == true))
                                    downloadState = DownloadState.Idle
                            },
                            title = { Text("❌ 更新失败", fontWeight = FontWeight.Bold) },
                            text = { Text(ds.reason) },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        val decision = updateDecision
                                        if (decision != null) {
                                            ctx.startUpdateDownload(decision, scope) { downloadState = it }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFE25C8A)
                                    )
                                ) { Text("重试") }
                            },
                            dismissButton = {
                                if (!(updateDecision?.forced == true)) {
                                    TextButton(onClick = { downloadState = DownloadState.Idle }) { Text("关闭") }
                                }
                            },
                            properties = DialogProperties(
                                dismissOnBackPress = !(updateDecision?.forced == true),
                                dismissOnClickOutside = !(updateDecision?.forced == true)
                            )
                        )
                    }
                    DownloadState.Idle -> { /* 无操作 */ }
                }

                // —— 强制更新 Dialog：不可关闭 ——
                val decision = updateDecision
                if (decision != null && decision.forced && downloadState is DownloadState.Idle && !downloadDone) {
                    AlertDialog(
                        onDismissRequest = { /* 强制更新，不允许关闭 */ },
                        title = {
                            Text(
                                decision.info.title.ifBlank { "必须更新才能使用" },
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Column {
                                Text(decision.info.message.ifBlank {
                                    "当前版本过低，云端已强制要求更新至 v${decision.info.versionName}。" +
                                            "\n请点击下方按钮下载并安装新版。"
                                })
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "本地版本: $localVN ($localVC)\n" +
                                            "新版本: ${decision.info.versionName} (${decision.info.versionCode})" +
                                            if (decision.info.md5.isNotBlank()) "\n已启用 MD5 校验" else "",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { ctx.startUpdateDownload(decision, scope) { downloadState = it } },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFE25C8A),
                                    contentColor = Color.White
                                )
                            ) { Text("立即下载并更新") }
                        },
                        dismissButton = { },
                        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
                    )
                } else if (decision != null && decision.hasUpdate && downloadState is DownloadState.Idle && !downloadDone) {
                    // 可选更新
                    var showOptional by remember { mutableStateOf(true) }
                    if (showOptional) {
                        AlertDialog(
                            onDismissRequest = { showOptional = false },
                            title = { Text(decision.info.title.ifBlank { "发现新版本" }) },
                            text = {
                                Column {
                                    Text(decision.info.message.ifBlank { "可选更新：v${decision.info.versionName}" })
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "新版本: ${decision.info.versionName} (${decision.info.versionCode})" +
                                                if (decision.info.md5.isNotBlank()) "\n已启用 MD5 校验" else "",
                                        fontSize = 12.sp, color = Color.Gray
                                    )
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        ctx.startUpdateDownload(decision, scope) { downloadState = it }
                                        showOptional = false
                                    }
                                ) { Text("立即更新") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showOptional = false }) { Text("稍后") }
                            }
                        )
                    }
                }
                NyaAppScreen(prefs = prefs, activity = this@MainActivity)
            }
        }
    }

    private fun getLocalVersion(): Pair<Int, String> {
        return runCatching {
            val pkgInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val vc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode
            }
            vc to (pkgInfo.versionName ?: "1.0.0")
        }.getOrDefault(0 to "1.0.0")
    }

    /** 启动常驻通知前台服务（Android 13+ 先请求通知权限） */
    private fun ensureForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startForegroundServiceCompat()
    }

    private fun startForegroundServiceCompat() {
        runCatching {
            val i = Intent(this, NyaForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, i)
            } else {
                startService(i)
            }
        }
    }
}

// =========================================================
//  Compose UI
// =========================================================

/** 格式化字节数为人类可读 */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1fKB", kb)
    val mb = kb / 1024.0
    return String.format("%.1fMB", mb)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NyaAppScreen(
    prefs: NyaPrefs,
    activity: MainActivity
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val masterEnabled by prefs.masterEnabled.collectAsStateWithLifecycle(initialValue = true)
    val appendContent by prefs.appendContent.collectAsStateWithLifecycle(initialValue = "喵")
    val isGlobalMode by prefs.isGlobalMode.collectAsStateWithLifecycle(initialValue = true)
    val appendMode by prefs.appendMode.collectAsStateWithLifecycle(initialValue = AppendMode.IDLE)
    val idleDelayMs by prefs.idleDelayMs.collectAsStateWithLifecycle(initialValue = 1200)
    val punctuationDelayMs by prefs.punctuationDelayMs.collectAsStateWithLifecycle(initialValue = 700)
    val kaomojiEnabled by prefs.kaomojiEnabled.collectAsStateWithLifecycle(initialValue = false)
    val customKaomojis by prefs.customKaomojis.collectAsStateWithLifecycle(initialValue = "")

    var a11yEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(ctx)) }
    val serviceRunning = NyaAccessibilityService.isRunning()

    // 手动检测：用户手动点击后刷新 tick → 强制重新计算权限状态
    var refreshTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshTick) {
        a11yEnabled = withContext(Dispatchers.Default) { isAccessibilityServiceEnabled(ctx) }
    }
    val serviceRunningNow = remember(refreshTick) { NyaAccessibilityService.isRunning() }

    var showContentDialog by remember { mutableStateOf(false) }
    var showKaomojiDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("🐱 喵输入法助手", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                },
                actions = {
                    TextButton(onClick = {
                        val i = Intent(activity, AboutActivity::class.java)
                        activity.startActivity(i)
                    }) {
                        Text("关于", color = Color(0xFFE91E63), fontWeight = FontWeight.Medium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFFFF5F8)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFFFFFBFC))
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            // ===== 状态卡片 =====
            SectionTitle("当前状态")
            Spacer(Modifier.height(8.dp))
            StatusGrid(
                a11yEnabled = a11yEnabled,
                serviceRunning = serviceRunningNow,
                masterEnabled = masterEnabled,
                isGlobalMode = isGlobalMode,
                appendMode = appendMode
            )

            Spacer(Modifier.height(20.dp))

            // ===== 功能设置 =====
            SectionTitle("功能设置")
            Spacer(Modifier.height(8.dp))

            SwitchRow(
                title = "启用自动追加功能",
                desc = "关闭后所有应用都不会追加文本",
                checked = masterEnabled,
                onCheckedChange = { scope.launch { prefs.setMasterEnabled(it) } }
            )

            // 自定义追加内容
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .clickable { showContentDialog = true }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("✏️", fontSize = 20.sp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("追加文本内容", fontWeight = FontWeight.Medium)
                    Text("当前：「 $appendContent 」，点击可自定义", color = Color.Gray, fontSize = 12.sp)
                }
                Text("›", color = Color.Gray, fontSize = 20.sp)
            }
            HorizontalDivider(color = Color(0x22000000))

            // 随机喵颜文字
            SwitchRow(
                title = "追加喵颜文字",
                desc = "在「$appendContent」后再随机拼一个喵相关颜文字",
                checked = kaomojiEnabled,
                onCheckedChange = { scope.launch { prefs.setKaomojiEnabled(it) } }
            )
            if (kaomojiEnabled) {
                val customCount = customKaomojis.lineSequence().map { it.trim() }.count { it.isNotEmpty() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                        .clickable { showKaomojiDialog = true }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🎨", fontSize = 20.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自定义颜文字库", fontWeight = FontWeight.Medium)
                        Text(
                            "默认库 ${NyaPrefs.DEFAULT_KAOMOJIS.size} 个，自定义 $customCount 个，点击管理",
                            color = Color.Gray, fontSize = 12.sp
                        )
                        Text(
                            "预览示例：${NyaPrefs.DEFAULT_KAOMOJIS.first()}  ${NyaPrefs.DEFAULT_KAOMOJIS[1]}  ${NyaPrefs.DEFAULT_KAOMOJIS[2]}",
                            fontSize = 11.sp, color = Color(0xFFE25C8A),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Text("›", color = Color.Gray, fontSize = 20.sp)
                }
            }
            HorizontalDivider(color = Color(0x22000000))

            // 追加模式
            SectionLabel("自动追加模式")
            Spacer(Modifier.height(4.dp))
            RadioRow(
                title = "实时停顿追加",
                desc = "用户停顿 ${String.format("%.1f", idleDelayMs.toFloat()/1000)} 秒后自动追加；继续输入时撤回并重新计时",
                selected = appendMode == AppendMode.IDLE,
                onClick = { scope.launch { prefs.setAppendMode(AppendMode.IDLE) } }
            )
            if (appendMode == AppendMode.IDLE) {
                DelaySliderCard(
                    label = "停顿追加延迟",
                    seconds = idleDelayMs.toFloat() / 1000,
                    minSeconds = 0.3f,
                    maxSeconds = 5.0f,
                    onSecondsChanged = { s ->
                        scope.launch { prefs.setIdleDelayMs((s * 1000).toInt()) }
                    }
                )
                Spacer(Modifier.height(6.dp))
            }
            RadioRow(
                title = "标点符号后追加",
                desc = "仅当输入末尾是标点（。，！？等）时 ${String.format("%.1f", punctuationDelayMs.toFloat()/1000)} 秒后追加",
                selected = appendMode == AppendMode.PUNCTUATION,
                onClick = { scope.launch { prefs.setAppendMode(AppendMode.PUNCTUATION) } }
            )
            if (appendMode == AppendMode.PUNCTUATION) {
                DelaySliderCard(
                    label = "标点追加延迟",
                    seconds = punctuationDelayMs.toFloat() / 1000,
                    minSeconds = 0.2f,
                    maxSeconds = 5.0f,
                    onSecondsChanged = { s ->
                        scope.launch { prefs.setPunctuationDelayMs((s * 1000).toInt()) }
                    }
                )
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(20.dp))

            // ===== 生效范围 =====
            SectionTitle("生效范围")
            Spacer(Modifier.height(8.dp))
            RadioRow(
                title = "全局生效",
                desc = "所有 App 输入完毕均自动追加（推荐）",
                selected = isGlobalMode,
                onClick = { scope.launch { prefs.setGlobalMode(true) } }
            )
            RadioRow(
                title = "部分应用生效",
                desc = "仅勾选的应用生效，点击进入选择",
                selected = !isGlobalMode,
                onClick = {
                    scope.launch {
                        prefs.setGlobalMode(false)
                        val i = Intent(ctx, AppPickerActivity::class.java)
                        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        ctx.startActivity(i)
                    }
                }
            )

            Spacer(Modifier.height(24.dp))

            // ===== 无障碍服务 =====
            SectionTitle("无障碍服务")
            Spacer(Modifier.height(8.dp))

            if (!a11yEnabled) {
                Button(
                    onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE25C8A),
                        contentColor = Color.White
                    )
                ) {
                    Text("开启无障碍服务", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "点击上方按钮 → 在系统设置中找到「喵输入法助手」并开启",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            } else {
                OutlinedButton(
                    onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("已开启（点击重新进入无障碍设置）")
                }
            }

            Spacer(Modifier.height(12.dp))

            // 手动检测按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { refreshTick++ },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("🔄 手动检测权限状态")
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (a11yEnabled) Icons.Default.CheckCircle else Icons.Default.Close,
                            contentDescription = null,
                            tint = if (a11yEnabled) Color(0xFF4CAF50) else Color(0xFFF44336),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (a11yEnabled) "权限已授予" else "权限未授予",
                            fontSize = 12.sp,
                            color = if (a11yEnabled) Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (serviceRunningNow) Icons.Default.CheckCircle else Icons.Default.Close,
                            contentDescription = null,
                            tint = if (serviceRunningNow) Color(0xFF4CAF50) else Color(0xFFF44336),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (serviceRunningNow) "服务运行中" else "服务未运行",
                            fontSize = 12.sp,
                            color = if (serviceRunningNow) Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "提示：若授权后仍显示未运行，可重启 App 或多次点击「手动检测」刷新",
                fontSize = 11.sp,
                color = Color.Gray
            )

            Spacer(Modifier.height(32.dp))
            Text(
                "by 掘尺川",
                fontSize = 12.sp,
                color = Color(0xFFE25C8A),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    // ---------- 自定义内容 Dialog ----------
    if (showContentDialog) {
        var draft by remember { mutableStateOf(appendContent) }
        AlertDialog(
            onDismissRequest = { showContentDialog = false },
            title = { Text("自定义追加内容") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("文本内容") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("留空将使用默认「喵」字", fontSize = 12.sp, color = Color.Gray)
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        prefs.setAppendContent(draft.ifBlank { "喵" })
                        showContentDialog = false
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showContentDialog = false }) { Text("取消") }
            }
        )
    }

    // ---------- 自定义颜文字库 Dialog ----------
    if (showKaomojiDialog) {
        var draft by remember { mutableStateOf(customKaomojis) }
        AlertDialog(
            onDismissRequest = { showKaomojiDialog = false },
            title = { Text("自定义颜文字库") },
            text = {
                Column {
                    Text("每行一个颜文字，App 会在默认库基础上叠加使用；\n全部清空表示仅使用默认库（共 ${NyaPrefs.DEFAULT_KAOMOJIS.size} 个）。",
                        fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("自定义颜文字") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        maxLines = 10
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { draft = "" }) {
                            Text("清空自定义", color = Color(0xFFE25C8A), fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            draft = NyaPrefs.DEFAULT_KAOMOJIS.take(8).joinToString("\n")
                        }) {
                            Text("填充默认示例", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        prefs.setCustomKaomojis(draft)
                        showKaomojiDialog = false
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showKaomojiDialog = false }) { Text("取消") }
            }
        )
    }
}

// ========================
//  UI 小组件
// ========================

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = Color(0xFFE25C8A)
    )
}

@Composable
private fun StatusGrid(
    a11yEnabled: Boolean,
    serviceRunning: Boolean,
    masterEnabled: Boolean,
    isGlobalMode: Boolean,
    appendMode: AppendMode
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            StatusRow("无障碍服务（系统层）", a11yEnabled)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            StatusRow("无障碍服务（运行中）", serviceRunning)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            StatusRow("功能总开关", masterEnabled)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("生效范围", color = Color.Gray)
                Text(
                    if (isGlobalMode) "全局（所有App）" else "部分应用",
                    fontWeight = FontWeight.Medium,
                    color = if (isGlobalMode) Color(0xFF188038) else Color(0xFFE25C8A)
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("追加模式", color = Color.Gray)
                Text(
                    if (appendMode == AppendMode.IDLE) "停顿追加" else "标点追加",
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFE25C8A)
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.Gray)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = if (ok) Color(0xFF188038) else Color(0xFFD93025)
            Icon(
                imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Close,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (ok) "正常" else "未就绪",
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("✨", fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(desc, fontSize = 12.sp, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider(color = Color(0x22000000))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        color = Color(0xFFE25C8A),
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
    )
}

/**
 * 延迟时间滑块卡片：显示当前秒数 + 可拖动的 Slider
 */
@Composable
private fun DelaySliderCard(
    label: String,
    seconds: Float,
    minSeconds: Float,
    maxSeconds: Float,
    onSecondsChanged: (Float) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF5F8)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = Color(0xFF5D4037)
                )
                Text(
                    "${String.format("%.1f", seconds)} 秒",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = Color(0xFFE25C8A)
                )
            }
            Slider(
                value = seconds,
                valueRange = minSeconds..maxSeconds,
                onValueChange = onSecondsChanged,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFE25C8A),
                    activeTrackColor = Color(0xFFF48FB1),
                    inactiveTrackColor = Color(0x33E91E63)
                ),
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            // 快捷预设按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val presets = floatArrayOf(0.5f, 1.0f, 2.0f, 3.0f)
                presets.forEach { preset ->
                    val label: String = if (preset < 1f) "${(preset * 1000).toInt()}ms" else "${preset.toInt()}s"
                    val selected = kotlin.math.abs(seconds - preset) < 0.05f
                    AssistChip(
                        onClick = { onSecondsChanged(preset) },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (selected) Color(0xFFFFCDD2) else Color.White,
                            labelColor = Color(0xFF5D4037)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = Color(0x22E91E63)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioRow(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = Color(0xFFE25C8A)
            )
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(desc, fontSize = 12.sp, color = Color.Gray)
        }
    }
    HorizontalDivider(color = Color(0x22000000))
}

// ========================
//  辅助方法
// ========================

private fun isAccessibilityServiceEnabled(ctx: android.content.Context): Boolean {
    val enabled = runCatching {
        Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
    }.getOrDefault(false)
    if (!enabled) return false
    val targetComponent = "${ctx.packageName}/com.nya.app.service.NyaAccessibilityService"
    val services = Settings.Secure.getString(
        ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return services.split(':').any { it.equals(targetComponent, ignoreCase = true) }
}
