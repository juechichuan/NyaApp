package com.nya.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "NyaUpdate"

// 云端 update.json 地址 —— 使用 GitHub main 分支 Raw 直链
// 你每次发布新版本只需要改仓库根目录 update.json 里的 versionCode 和 downloadUrl
const val UPDATE_JSON_URL =
    "https://raw.githubusercontent.com/juechichuan/NyaApp/main/update.json"

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val minVersionCode: Int,
    val force: Boolean,
    val downloadUrl: String,
    val title: String,
    val message: String
) {
    /** 本地版本与云端对比的结果 */
    fun evaluate(localVersionCode: Int): UpdateDecision {
        val hasUpdate = versionCode > localVersionCode
        val mustUpdate = force && hasUpdate
        val belowMin = localVersionCode < minVersionCode
        return UpdateDecision(
            hasUpdate = hasUpdate,
            forced = mustUpdate || belowMin,
            info = this
        )
    }
}

data class UpdateDecision(
    val hasUpdate: Boolean,
    val forced: Boolean,
    val info: UpdateInfo
)

/** 拉取云端版本配置 JSON 并解析成 UpdateInfo */
suspend fun fetchUpdateInfo(): UpdateInfo? = withContext(Dispatchers.IO) {
    runCatching {
        val url = URL(UPDATE_JSON_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.setRequestProperty("Accept", "application/json")
        conn.connect()
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val jo = JSONObject(text)
        UpdateInfo(
            versionCode = jo.optInt("versionCode", 0),
            versionName = jo.optString("versionName", ""),
            minVersionCode = jo.optInt("minVersionCode", 0),
            force = jo.optBoolean("force", false),
            downloadUrl = jo.optString("downloadUrl", ""),
            title = jo.optString("title", "发现新版本"),
            message = jo.optString("message", "")
        ).takeIf { it.versionCode > 0 && it.downloadUrl.isNotBlank() }
    }.onFailure { Log.e(TAG, "拉取 update.json 失败", it) }.getOrNull()
}

/** 下载 APK 并调用系统安装器（强制更新）。返回 DownloadManager 分配的 downloadId */
fun enqueueUpdateDownload(context: Context, updateInfo: UpdateInfo): Long {
    val apkFile = File(
        context.cacheDir,
        "apk/NyaApp-v${updateInfo.versionName}-${updateInfo.versionCode}.apk"
    )
    if (apkFile.parentFile?.exists() != true) apkFile.parentFile?.mkdirs()
    if (apkFile.exists()) apkFile.delete()

    val request = DownloadManager.Request(Uri.parse(updateInfo.downloadUrl)).apply {
        setTitle(updateInfo.title.ifBlank { "🐱 喵输入法助手更新" })
        setDescription(updateInfo.message.ifBlank { "正在下载新版本..." })
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        setDestinationUri(Uri.fromFile(apkFile))
        allowScanningByMediaScanner()
        setAllowedOverMetered(true)
        setAllowedOverRoaming(true)
    }
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    // 注册下载完成接收器 → 自动弹出安装
    context.registerReceiver(
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                val cursor: Cursor? = dm.query(DownloadManager.Query().setFilterById(id))
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val localUri =
                                c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                            val file = File(Uri.parse(localUri).path!!)
                            tryInstallApk(ctx, file)
                        }
                    }
                }
                runCatching { ctx.unregisterReceiver(this) }
            }
        },
        IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Context.RECEIVER_NOT_EXPORTED else 0
    )

    return dm.enqueue(request)
}

/** 调起系统 APK 安装器 */
fun tryInstallApk(context: Context, apkFile: File) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        // Android 8.0+ 先检查是否允许安装未知来源，不允许则引导用户授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pm = context.packageManager
            val allowed = pm.canRequestPackageInstalls()
            if (!allowed) {
                val settingsIntent = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settingsIntent)
                return
            }
        }
        context.startActivity(intent)
    }.onFailure { Log.e(TAG, "唤起安装器失败", it) }
}

// ===================== 调试用：下载文件是否完整 =====================
fun isApkFileValid(file: File): Boolean {
    if (!file.exists()) return false
    if (file.length() < 1024 * 1024) return false // 小于 1MB 一定坏了
    return file.extension.equals("apk", true) || file.name.endsWith(".apk", true)
}
