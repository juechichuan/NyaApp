package com.nya.app

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import com.nya.app.data.NyaPrefs
import com.nya.app.security.SignatureGuard
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NyaApplication : Application() {

    companion object {
        lateinit var instance: NyaApplication
            private set
        var lastCrash: String? = null
            private set
    }

    lateinit var prefs: NyaPrefs
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
        // 反篡改第一道防线：签名校验 + 防调试，不通过直接 kill 进程
        // 放在 attachBaseContext 中尽早执行，确保后续逻辑无法被注入
        SignatureGuard.verifyOnAppStart(this)
    }

    override fun onCreate() {
        super.onCreate()
        installGlobalCrashHandler()
        prefs = NyaPrefs(this)
    }

    /**
     * 全局未捕获异常兜底：写入本地 crash 文件，方便排查问题。
     */
    private fun installGlobalCrashHandler() {
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            PrintWriter(sw).use { pw -> throwable.printStackTrace(pw) }
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val report = buildString {
                append("=== NyaApp crash @ ").append(ts).append(" ===\n")
                append("Thread: ").append(thread.name).append(" (").append(thread.id).append(")\n")
                append(sw.toString())
            }
            lastCrash = report
            Log.e("NyaApplication", "Crash captured: $report")
            runCatching {
                val f = File(filesDir, "last_crash.txt")
                f.writeText(report)
            }
            runCatching { oldHandler?.uncaughtException(thread, throwable) }
                .getOrElse {
                    Process.killProcess(Process.myPid())
                    System.exit(2)
                }
        }
    }
}
