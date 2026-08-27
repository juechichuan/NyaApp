package com.nya.app.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.nya.app.NyaApplication
import com.nya.app.data.NyaPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AppPickerActivity"

/**
 * 应用选择界面（独立 Activity，规避 Compose Dialog 在 ColorOS 15 上崩溃的问题）
 *
 * 用途：用户在主界面选择"部分应用生效"后跳转到此页面，
 * 可勾选/取消勾选已安装应用，勾选的应用会被加入白名单。
 */
class AppPickerActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = (application as NyaApplication).prefs
        var initialWhitelist by mutableStateOf<Set<String>>(emptySet())

        // 后台加载应用列表（避免阻塞 UI 线程，规避 ColorOS ANR 判定）
        var apps by mutableStateOf<List<AppInfo>>(emptyList())
        var loading by mutableStateOf(true)

        lifecycleScope.launch {
            // 1) 先读出当前白名单
            initialWhitelist = prefs.snapshotBlocking().whitelistPackages
            // 2) 后台查询应用列表
            val list = withContext(Dispatchers.IO) {
                runCatching { queryUserApps() }.getOrElse {
                    Log.e(TAG, "查询应用列表失败", it)
                    emptyList()
                }
            }
            apps = list
            loading = false
        }

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFFE91E63),
                secondary = Color(0xFFF48FB1),
                tertiary = Color(0xFF7C4DFF)
            )) {
                AppPickerScreen(
                    apps = apps,
                    loading = loading,
                    initialWhitelist = initialWhitelist,
                    onClose = { finish() },
                    onSave = { newWhitelist ->
                        lifecycleScope.launch {
                            prefs.setWhitelistPackages(newWhitelist)
                            finish()
                        }
                    }
                )
            }
        }
    }

    /**
     * 查询已安装的第三方应用（排除系统应用和本应用）。
     * 只取有启动 Intent 的应用，避免列表过长。
     */
    private fun queryUserApps(): List<AppInfo> {
        val pm = packageManager
        val all = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return all
            .filter { info ->
                // 排除本应用自身
                info.packageName != packageName &&
                // 必须有可启动 Intent（排除库 / 服务类应用）
                pm.getLaunchIntentForPackage(info.packageName) != null
            }
            .map { info ->
                AppInfo(
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedWith(
                compareBy<AppInfo> { it.isSystem }  // 第三方应用在前
                    .thenBy { it.label }              // 按名称排序
            )
    }
}

data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerScreen(
    apps: List<AppInfo>,
    loading: Boolean,
    initialWhitelist: Set<String>,
    onClose: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    // 当前勾选状态：包名 -> 是否勾选
    val checked = remember(apps, initialWhitelist) {
        mutableStateMapOf<String, Boolean>().apply {
            apps.forEach { put(it.packageName, it.packageName in initialWhitelist) }
        }
    }
    // 搜索关键字
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择生效应用", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val selected = checked.filter { it.value }.keys
                        onSave(selected)
                    }) { Text("保存", fontWeight = FontWeight.Bold) }
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
        ) {
            // 搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索应用名称或包名") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )

            // 统计栏
            val checkedCount = checked.count { it.value }
            Text(
                "已选 $checkedCount / ${apps.size} 个应用",
                fontSize = 13.sp,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFFE25C8A))
                        Spacer(Modifier.height(8.dp))
                        Text("正在加载应用列表...", fontSize = 13.sp, color = Color.Gray)
                    }
                }
                return@Scaffold
            }

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("没有匹配的应用", color = Color.Gray)
                }
                return@Scaffold
            }

            // 应用列表（LazyColumn 在独立 Activity 内使用，不会触发 Dialog 内的测量死循环）
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val isChecked = checked[app.packageName] ?: false
                    AppRow(
                        app = app,
                        checked = isChecked,
                        onCheckedChange = { newValue ->
                            checked[app.packageName] = newValue
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFFE25C8A)
            )
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, fontWeight = FontWeight.Medium)
            Text(
                app.packageName + if (app.isSystem) " · 系统" else "",
                fontSize = 11.sp,
                color = Color.Gray
            )
        }
    }
    HorizontalDivider(color = Color(0x11000000))
}
