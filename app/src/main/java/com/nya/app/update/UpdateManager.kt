package com.nya.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val TAG = "NyaUpdate"

// 云端 update.json 地址
const val UPDATE_JSON_URL =
    "https://raw.githubusercontent.com/juechichuan/NyaApp/main/update.json"

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val minVersionCode: Int,
    val force: Boolean,
    val downloadUrl: String,
    val title: String,
    val message: String,
    val md5: String
) {
    fun evaluate(localVersionCode: Int): UpdateDecision {
        val hasUpdate = versionCode > localVersionCode
        val mustUpdate = force && hasUpdate
        val belowMin = localVersionCode < minVersionCode
        return UpdateDecision(hasUpdate, mustUpdate || belowMin, this)
    }
}

data class UpdateDecision(
    val hasUpdate: Boolean,
    val forced: Boolean,
    val info: UpdateInfo
)

/** 下载进度回调 */
sealed class DownloadState {
    object Idle : DownloadState()
    data class Progress(val percent: Int, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    object Verifying : DownloadState()
    data class Success(val apkFile: File) : DownloadState()
    data class Failed(val reason: String) : DownloadState()
}

/** 拉取云端版本配置 JSON 并解析 */
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
            message = jo.optString("message", ""),
            md5 = jo.optString("md5", "").trim().lowercase()
        ).takeIf { it.versionCode > 0 && it.downloadUrl.isNotBlank() }
    }.onFailure { Log.e(TAG, "拉取 update.json 失败", it) }.getOrNull()
}

/**
 * 自定义流式下载 APK + MD5 校验。
 * 通过 [onProgress] 回调实时报告下载百分比（0~100）。
 * 下载完成后自动校验 MD5，不匹配则返回 Failed。
 */
suspend fun downloadApkWithProgress(
    context: Context,
    updateInfo: UpdateInfo,
    onProgress: (DownloadState) -> Unit
): Unit = withContext(Dispatchers.IO) {
    val apkFile = File(
        context.cacheDir,
        "apk/NyaApp-v${updateInfo.versionName}-${updateInfo.versionCode}.apk"
    )
    if (apkFile.parentFile?.exists() != true) apkFile.parentFile?.mkdirs()
    if (apkFile.exists()) apkFile.delete()

    var conn: HttpURLConnection? = null
    var input: InputStream? = null
    var output: FileOutputStream? = null

    runCatching {
        val url = URL(updateInfo.downloadUrl)
        conn = url.openConnection() as HttpURLConnection
        conn!!.connectTimeout = 15000
        conn!!.readTimeout = 30000
        conn!!.setRequestProperty("Accept", "application/vnd.android.package-archive")
        conn!!.instanceFollowRedirects = true
        conn!!.connect()

        val code = conn!!.responseCode
        if (code != 200) {
            onProgress(DownloadState.Failed("服务器返回 HTTP $code"))
            return@runCatching
        }

        val total = conn!!.contentLengthLong  // -1 if unknown
        input = conn!!.inputStream
        output = FileOutputStream(apkFile)

        val buffer = ByteArray(8192)
        var downloaded = 0L
        var lastReportedPercent = -1

        while (true) {
            val read = input!!.read(buffer)
            if (read <= 0) break
            output!!.write(buffer, 0, read)
            downloaded += read
            if (total > 0) {
                val percent = (downloaded * 100 / total).toInt()
                if (percent != lastReportedPercent && percent % 2 == 0) {
                    lastReportedPercent = percent
                    onProgress(DownloadState.Progress(percent, downloaded, total))
                }
            }
        }
        output!!.flush()
        onProgress(DownloadState.Progress(100, if (total > 0) total else apkFile.length(), total))

        // —— MD5 校验 ——
        onProgress(DownloadState.Verifying)
        if (updateInfo.md5.isNotBlank()) {
            val actualMd5 = computeMd5(apkFile)
            if (actualMd5 != updateInfo.md5) {
                Log.e(TAG, "MD5 校验失败: expected=${updateInfo.md5} actual=$actualMd5")
                apkFile.delete()
                onProgress(DownloadState.Failed("MD5 校验失败，文件可能被篡改或下载不完整"))
                return@runCatching
            }
            Log.i(TAG, "MD5 校验通过 ✓")
        } else {
            Log.w(TAG, "云端未提供 md5，跳过校验")
        }

        if (apkFile.length() < 1024 * 1024) {
            onProgress(DownloadState.Failed("下载文件过小 (${apkFile.length()} bytes)，可能不完整"))
            return@runCatching
        }

        onProgress(DownloadState.Success(apkFile))

    }.onFailure { e ->
        Log.e(TAG, "下载失败", e)
        apkFile.delete()
        onProgress(DownloadState.Failed("下载异常: ${e.message ?: "未知错误"}"))
    }

    runCatching { input?.close() }
    runCatching { output?.close() }
    runCatching { conn?.disconnect() }
}

/** 计算文件 MD5（32位小写十六进制） */
private fun computeMd5(file: File): String {
    val md = MessageDigest.getInstance("MD5")
    FileInputStream(file).use { fis ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = fis.read(buffer)
            if (read <= 0) break
            md.update(buffer, 0, read)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val allowed = context.packageManager.canRequestPackageInstalls()
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
