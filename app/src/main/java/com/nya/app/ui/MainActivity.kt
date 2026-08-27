package com.nya.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nya.app.NyaApplication
import com.nya.app.data.NyaPrefs
import com.nya.app.service.NyaAccessibilityService
import com.nya.app.shizuku.ShizukuManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var shizuku: ShizukuManager
    private lateinit var prefs: NyaPrefs

    /** 当前启动生命周期内是否已经自动请求过一次（避免 onResume 反复弹） */
    private var autoRequestedThisLaunch = false

    /** Shizuku 授权结果回调（只注册一次，在 onDestroy 移除） */
    private val permissionResultListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ ->
        // 结果到达时刷新界面
        invalidateUi()
    }

    private fun invalidateUi() {
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFFE91E63),
                secondary = Color(0xFFF48FB1),
                tertiary = Color(0xFF7C4DFF)
            )) {
                NyaAppScreen(shizuku = shizuku, prefs = prefs, activity = this@MainActivity)
            }
        }
    }

    private fun autoRequestShizukuPermissionIfNeeded() {
        // 1) 已授权 -> 不用请求
        if (shizuku.isShizukuReady()) return
        // 2) Shizuku / Sui 服务本身没起来 (pingBinder 失败) -> 不用请求，提示留给 UI
        if (!kotlin.runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)) return
        // 3) 避免 onResume 反复请求 -> 每个前台生命周期最多一次
        if (autoRequestedThisLaunch) return
        autoRequestedThisLaunch = true
        // 4) 静默请求，不弹 Toast；失败后用户仍可通过 UI 按钮手动操作
        Log.d("MainActivity", "autoRequestShizukuPermissionIfNeeded: requesting permission...")
        shizuku.requestPermission(this) { granted ->
            Log.d("MainActivity", "autoRequestShizukuPermissionIfNeeded: granted=$granted")
            invalidateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shizuku = (application as NyaApplication).shizuku
        prefs = (application as NyaApplication).prefs
        // 注册 Shizuku 授权结果监听（官方推荐：监听覆盖授权后外部弹窗关闭的情况）
        kotlin.runCatching {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }
        invalidateUi()
        autoRequestShizukuPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // 状态可能变化（用户中途在 Shizuku 授予 / 切回），刷新 UI
        invalidateUi()
        // 尝试自动请求（若 onCreate 时服务还没起来，现在可能好了）
        autoRequestShizukuPermissionIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        kotlin.runCatching {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        }
    }
}

// =========================================================
//  Compose UI
// =========================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NyaAppScreen(
    shizuku: ShizukuManager,
    prefs: NyaPrefs,
    activity: MainActivity
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 偏好设置（随用户修改实时更新）
    val masterEnabled by prefs.masterEnabled.collectAsStateWithLifecycle(initialValue = true)
    val appendContent by prefs.appendContent.collectAsStateWithLifecycle(initialValue = "喵")
    val whitelist by prefs.whitelistPackages.collectAsStateWithLifecycle(initialValue = emptySet())

    // 刷新状态（Shizuku / 无障碍）
    var shizukuReady by remember { mutableStateOf(shizuku.isShizukuReady()) }
    var a11yEnabled by remember { mutableStateOf(shizuku.isAccessibilityServiceEnabled()) }
    val serviceRunning = NyaAccessibilityService.isRunning()

    // 选择 App Dialog
    var showAppPicker by remember { mutableStateOf(false) }
    // 内容编辑 dialog
    var showContentDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🐱 喵输入法助手", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
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
                .padding(16.dp)
        ) {
            // ===== 状态卡片 =====
            SectionTitle(text = "当前状态")
            Spacer(Modifier.height(8.dp))
            StatusGrid(
                shizukuReady = shizukuReady,
                a11yEnabled = a11yEnabled,
                serviceRunning = serviceRunning,
                masterEnabled = masterEnabled,
                whitelistSize = whitelist.size
            )

            Spacer(Modifier.height(20.dp))

            // ===== 功能设置 =====
            SectionTitle(text = "功能设置")
            Spacer(Modifier.height(8.dp))

            // 总开关
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
                Icon(Icons.Default.Edit, null, tint = Color(0xFFE25C8A))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("追加文本内容", fontWeight = FontWeight.Medium)
                    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
                        Text("当前：「 $appendContent 」，点击可自定义", color = Color.Gray)
                    }
                }
                Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
            }
            HorizontalDivider(color = Color(0x22000000))

            // 白名单选择
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .clickable { showAppPicker = true }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Apps, null, tint = Color(0xFFE25C8A))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("生效应用（白名单）", fontWeight = FontWeight.Medium)
                    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
                        val n = whitelist.size
                        Text(
                            if (n == 0) "当前：未选择（全局不会生效）" else "已选择 $n 个应用",
                            color = if (n == 0) Color(0xFFD93025) else Color.Gray
                        )
                    }
                }
                Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
            }
            HorizontalDivider(color = Color(0x22000000))

            Spacer(Modifier.height(24.dp))

            // ===== 高级操作 =====
            SectionTitle(text = "高级操作")
            Spacer(Modifier.height(8.dp))

            // 授权 Shizuku
            ActionButton(
                title = if (shizukuReady) "✓ Shizuku 授权正常（点击重新检测）" else "① 授权 Shizuku",
                subtitle = "需要先安装 Shizuku App 或 Sui，并通过 adb 启动服务",
                primary = !shizukuReady,
                icon = Icons.Default.Security
            ) {
                shizuku.requestPermission(activity) {
                    shizukuReady = shizuku.isShizukuReady()
                    a11yEnabled = shizuku.isAccessibilityServiceEnabled()
                }
            }

            Spacer(Modifier.height(8.dp))

            // 一键开启无障碍（依赖 Shizuku）
            ActionButton(
                title = if (a11yEnabled) "✓ 无障碍服务已启用（点击重新检测）" else "② 一键开启无障碍服务",
                subtitle = if (a11yEnabled) "已在系统设置中启用"
                            else if (shizukuReady) "将通过 Shizuku 自动写入系统设置，无需手动翻找"
                            else "请先完成 Shizuku 授权",
                primary = !a11yEnabled && shizukuReady,
                enabled = !a11yEnabled,
                icon = Icons.Default.TouchApp
            ) {
                if (!shizukuReady) {
                    android.widget.Toast.makeText(ctx, "请先授权 Shizuku", android.widget.Toast.LENGTH_SHORT).show()
                    return@ActionButton
                }
                val ok = shizuku.enableAccessibilityServiceByShizuku()
                android.widget.Toast.makeText(
                    ctx,
                    if (ok) "已开启，若未立即生效请重启无障碍" else "开启失败，请手动打开下方无障碍设置",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                // 300ms 后刷新状态
                androidx.core.os.HandlerCompat.postDelayed(
                    android.os.Handler(ctx.mainLooper),
                    {
                        shizukuReady = shizuku.isShizukuReady()
                        a11yEnabled = shizuku.isAccessibilityServiceEnabled()
                    },
                    null, 350
                )
            }

            Spacer(Modifier.height(8.dp))

            // 手动兜底
            OutlinedButton(
                onClick = { shizuku.openAccessibilitySettings(ctx) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, null)
                Spacer(Modifier.width(8.dp))
                Text("手动打开系统无障碍设置（兜底方案）")
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { shizuku.openShizukuAppOrInstall(activity) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.OpenInNew, null)
                Spacer(Modifier.width(8.dp))
                Text("打开/下载 Shizuku App")
            }
        }
    }

    // ---------- Dialogs ----------

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

    if (showAppPicker) {
        // ⚠️ 切到 IO 线程查询应用列表，避免主线程卡顿 -> ANR/闪退（Android 14+ 对主线程 IO 非常敏感）
        val installed by produceState<List<AppInfo>>(
            initialValue = emptyList(),
            key1 = whitelist
        ) {
            value = queryUserApps(ctx)
        }
        var selection by remember(whitelist) { mutableStateOf(whitelist.toMutableSet()) }
        var keyword by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            title = { Text("选择生效应用（白名单）") },
            containerColor = Color.White,
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = { Text("🔍 搜索应用名 / 包名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    val filtered = installed.filter {
                        keyword.isBlank() ||
                            it.label.contains(keyword, ignoreCase = true) ||
                            it.pkg.contains(keyword, ignoreCase = true)
                    }
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        // 用户安装的应用列表是 IO 查询 produceState 异步拿的，刚开始为 emptyList() -> 不显示闪退
                        if (installed.isEmpty() && keyword.isBlank()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(30.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = Color(0xFFE25C8A))
                                }
                            }
                        }
                        items(filtered, key = { it.pkg }) { app ->
                            val checked = selection.contains(app.pkg)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (checked) selection.remove(app.pkg)
                                        else selection.add(app.pkg)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = {
                                    if (it) selection.add(app.pkg) else selection.remove(app.pkg)
                                })
                                Spacer(Modifier.width(8.dp))
                                // 图标
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(
                                        android.R.drawable.sym_def_app_icon
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(30.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.label, fontWeight = FontWeight.Medium)
                                    Text(app.pkg, fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        prefs.setWhitelistPackages(selection)
                        showAppPicker = false
                    }
                }) { Text("保存（${selection.size}）") }
            },
            dismissButton = {
                TextButton(onClick = { showAppPicker = false }) { Text("取消") }
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
    shizukuReady: Boolean,
    a11yEnabled: Boolean,
    serviceRunning: Boolean,
    masterEnabled: Boolean,
    whitelistSize: Int
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            StatusRow("Shizuku 授权", shizukuReady)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
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
                Text("白名单已选应用数", color = Color.Gray)
                Text("${whitelistSize} 个", fontWeight = FontWeight.Medium)
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
                imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
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
        Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFFE25C8A))
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
private fun ActionButton(
    title: String,
    subtitle: String,
    primary: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFE25C8A),
                contentColor = Color.White
            ),
            contentPadding = PaddingValues(14.dp)
        ) {
            Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                Spacer(Modifier.height(3.dp))
                Text(subtitle, fontSize = 11.sp, color = Color(0xFFFFF0F4), lineHeight = 14.sp)
            }
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(14.dp)
        ) {
            Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, modifier = Modifier.size(20.dp), tint = Color(0xFF188038))
                    Spacer(Modifier.width(8.dp))
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color(0xFF188038))
                }
                Spacer(Modifier.height(3.dp))
                Text(subtitle, fontSize = 11.sp, color = Color.Gray, lineHeight = 14.sp)
            }
        }
    }
}

// ========================
//  查询用户已安装应用
// ========================
private data class AppInfo(val label: String, val pkg: String)

private suspend fun queryUserApps(ctx: android.content.Context): List<AppInfo> =
    withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        // 用已安装应用列表 -> 找能启动的 launcher intent（比 queryIntentActivities(ACTION_MAIN) 更稳，
        // 不会被"同包多入口 / 别名 activity"导致重复，也不会因为 MATCH_ALL 触发某些 ROM 的安全拦截）
        val installedPkgs: List<android.content.pm.ApplicationInfo> =
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledApplications(
                        android.content.pm.PackageManager.ApplicationInfoFlags.of(0L)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(0)
                }
            }.getOrDefault(emptyList())

        val results = mutableListOf<AppInfo>()
        val seenPkgs = HashSet<String>()

        for (appInfo in installedPkgs) {
            val pkg = appInfo.packageName ?: continue
            if (pkg == ctx.packageName) continue
            if (seenPkgs.contains(pkg)) continue

            // 必须要有桌面启动入口，否则对用户而言不是"可以打开发消息"的 App
            runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
                ?: continue

            val isSystem =
                (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem =
                (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            val label = runCatching {
                appInfo.loadLabel(pm)?.toString()?.trim()?.ifBlank { null }
            }.getOrNull() ?: pkg

            // 纯系统 App（没被用户升级过）：名字不规范 / 包名以 com.android.xxx 开头 → 过滤掉
            // （比如 com.android.settings 虽然有入口，但用户一般不会在里面发消息）
            if (isSystem && !isUpdatedSystem) {
                // 但保留主流常用系统 App：比如信息、拨号、日历、相机、便签等
                val whitelistedSystem = listOf(
                    "com.android.mms",
                    "com.google.android.apps.messaging",
                    "com.android.calendar",
                    "com.google.android.calendar"
                )
                if (pkg !in whitelistedSystem &&
                    (pkg.startsWith("com.android.") ||
                            pkg.startsWith("com.google.android.gms") ||
                            pkg.startsWith("android."))
                ) continue
            }
            if (label.isEmpty()) continue
            seenPkgs.add(pkg)
            results.add(AppInfo(label, pkg))
        }
        results.sortBy { it.label.lowercase() }
        results
    }
