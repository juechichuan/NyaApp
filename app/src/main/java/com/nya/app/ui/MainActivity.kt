package com.nya.app.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nya.app.NyaApplication
import com.nya.app.data.NyaPrefs
import com.nya.app.service.NyaAccessibilityService
import com.nya.app.shizuku.ShizukuManager
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
    val isGlobalMode by prefs.isGlobalMode.collectAsStateWithLifecycle(initialValue = true)

    // 刷新状态（Shizuku / 无障碍）
    var shizukuReady by remember { mutableStateOf(shizuku.isShizukuReady()) }
    var a11yEnabled by remember { mutableStateOf(shizuku.isAccessibilityServiceEnabled()) }
    val serviceRunning = NyaAccessibilityService.isRunning()

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
                isGlobalMode = isGlobalMode
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

            // 全局模式开关（默认开启：对所有App生效，无需选择应用，彻底规避应用列表崩溃问题）
            SwitchRow(
                title = "全局模式（对所有App生效）",
                desc = if (isGlobalMode) "已开启：所有应用输入完毕均自动追加"
                       else "已关闭：仅对已记录的App生效",
                checked = isGlobalMode,
                onCheckedChange = { scope.launch { prefs.setGlobalMode(it) } }
            )

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
    isGlobalMode: Boolean
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
                Text("生效模式", color = Color.Gray)
                Text(
                    if (isGlobalMode) "全局（所有App）" else "白名单模式",
                    fontWeight = FontWeight.Medium,
                    color = if (isGlobalMode) Color(0xFF188038) else Color(0xFFE25C8A)
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
