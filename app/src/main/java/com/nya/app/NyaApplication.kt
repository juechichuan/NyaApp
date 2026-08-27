package com.nya.app

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import com.nya.app.data.NyaPrefs
import com.nya.app.shizuku.ShizukuManager
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
    lateinit var shizuku: ShizukuManager
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
        // ShizukuProvider 会在被系统加载时自动初始化，无需手动调用
    }

    override fun onCreate() {
        super.onCreate()
        // 先注册全局异常兜底，再初始化其他组件
        installGlobalCrashHandler()
        prefs = NyaPrefs(this)
        shizuku = ShizukuManager(this)
    }

    /**
     * 全局未捕获异常兜底：任何地方的异常（包括后台协程）如果漏掉了 try/catch，
     * 这里会把异常写入本地 crash 文件并显示到下次启动的 UI 里（而不是直接"强制返回桌面"）。
     * 这样用户能看到具体原因，方便排查问题。
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
            // 持久化到本地，下次启动可以读出来提示用户
            runCatching {
                val f = File(filesDir, "last_crash.txt")
                f.writeText(report)
            }
            // 交给旧 handler（通常是系统杀掉进程），但至少我们已经留下了证据
            runCatching { oldHandler?.uncaughtException(thread, throwable) }
                .getOrElse {
                    // 兜底：旧 handler 抛异常也要结束进程，否则会进入死循环崩溃
                    Process.killProcess(Process.myPid())
                    System.exit(2)
                }
        }
    }
}
