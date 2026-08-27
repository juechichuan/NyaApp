package com.nya.app.shizuku

import android.os.ParcelFileDescriptor
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

private const val TAG = "NyaShellService"

/**
 * Shizuku UserService 实现：
 *  运行在 Shizuku 管理的独立进程里，身份是 shell（adb 启动时）或 root（Sui/Magisk 启动时）。
 *  在该进程内执行 shell 命令，获得相当于 `adb shell` 的权限，
 *  从而可以 `settings put secure` 写入系统安全设置。
 */
class NyaShellService : INyaShellService.Stub() {

    override fun execCommand(command: String): String {
        return runCatching {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command))
            val sb = StringBuilder()
            val stdout = process.inputStream
            val stderr = process.errorStream
            // 合并 stdout/stderr，先读 stdout
            BufferedReader(InputStreamReader(stdout)).useLines { lines ->
                lines.forEach { sb.append(it).append('\n') }
            }
            BufferedReader(InputStreamReader(stderr)).useLines { lines ->
                lines.forEach { sb.append("[E] ").append(it).append('\n') }
            }
            val exit = runCatching { process.waitFor() }.getOrDefault(-1)
            sb.append("\n__exit=$exit")
            sb.toString().ifBlank { "(empty, exit=$exit)" }
        }.getOrElse {
            Log.e(TAG, "execCommand failed: $command", it)
            "__exception=${it.javaClass.simpleName}: ${it.message}"
        }
    }
}
