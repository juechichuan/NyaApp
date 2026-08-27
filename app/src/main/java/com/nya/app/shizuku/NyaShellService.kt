package com.nya.app.shizuku

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "NyaShellService"

/**
 * Shizuku UserService 实现。
 * 运行在 Shizuku 管理的独立进程里，身份是 shell（adb 启动时）或 root（Sui/Magisk 启动时）。
 * 在该进程内直接用 Runtime.exec() 执行 shell 命令即可获得 shell 权限。
 */
class NyaShellService : INyaShellService.Stub() {

    override fun execCommand(command: String): String {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { sb.append(it).append('\n') }
            }
            BufferedReader(InputStreamReader(process.errorStream)).useLines { lines ->
                lines.forEach { sb.append("[E] ").append(it).append('\n') }
            }
            val exit = process.waitFor()
            sb.append("\n__exit=$exit")
            sb.toString().ifBlank { "(empty, exit=$exit)" }
        }.getOrElse {
            Log.e(TAG, "execCommand failed: $command", it)
            "__exception=${it.javaClass.simpleName}: ${it.message}"
        }
    }
}
