package com.nya.app.shizuku

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

private const val TAG = "ShizukuManager"
private const val REQUEST_CODE_PERMISSION = 10001

/**
 * Shizuku 授权管理 + 高权限调用封装。
 *
 * 核心能力：
 *  1. 检查 / 请求 Shizuku 权限（类似于 Android 运行时权限模型）
 *  2. 绑定 UserService（运行在 shell 权限下），用来执行 `settings put secure`
 *     从而**一键开启本 App 的无障碍服务**，无需用户手动进系统设置
 *
 * 注：`INyaShellService` AIDL + `NyaShellService` 都在同一个包下，
 *     UserService 启动时 Shizuku 框架会以 shell/root 身份加载此类。
 */
class ShizukuManager(private val context: Context) {

    // ------------ UserService 管理 ------------
    private val shellServiceArgs = UserServiceArgs(
        ComponentName(context, NyaShellService::class.java)
    ).apply {
        // daemon=true: 进程常驻，避免每次使用都重新 fork（adb 重启后需重连）
        daemon(false)
        // processNameSuffix 默认为空即可
    }

    @Volatile private var shellService: INyaShellService? = null

    private val userServiceConnection = object : Shizuku.UserServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
            shellService = INyaShellService.Stub.asInterface(iBinder)
            Log.i(TAG, "NyaShellService connected")
        }
        override fun onServiceDisconnected(componentName: ComponentName) {
            shellService = null
            Log.i(TAG, "NyaShellService disconnected")
        }
        override fun onBindingDied(componentName: ComponentName) {
            shellService = null
            Log.w(TAG, "NyaShellService binding died")
        }
        override fun onNullBinding(componentName: ComponentName) {
            Log.e(TAG, "NyaShellService null binding")
        }
    }

    /** 绑定到 UserService（已就绪会复用旧连接） */
    private fun ensureShellService(): INyaShellService? {
        shellService?.let { return it }
        if (!isShizukuReady()) return null
        return runCatching {
            Shizuku.bindUserService(shellServiceArgs, userServiceConnection)
            // 绑定是异步的；简单地自旋等待最多 1.2s，够系统把 binder 派发过来
            val dead = System.currentTimeMillis() + 1200
            while (System.currentTimeMillis() < dead) {
                val now = shellService
                if (now != null) return@runCatching now
                Thread.sleep(40)
            }
            shellService
        }.getOrNull()
    }

    // ------------ Shizuku 基础状态 ------------

    fun isShizukuReady(): Boolean {
        return runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun isShizukuAppInstalled(): Boolean {
        val pm = context.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo("moe.shizuku.privileged.api", android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo("moe.shizuku.privileged.api", 0)
            }
            true
        }.getOrElse {
            // Sui 无独立 App，但 getRemoteUserId() 会返回 "sui"
            runCatching {
                Shizuku.getRemoteUserId()?.lowercase() == "sui"
            }.getOrDefault(false)
        }
    }

    fun openShizukuAppOrInstall(activity: Activity) {
        val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent != null) {
            activity.startActivity(intent)
        } else {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 请求 Shizuku 权限；使用标准 OnRequestPermissionResultListener */
    fun requestPermission(activity: Activity, onResult: (granted: Boolean) -> Unit = {}) {
        if (isShizukuReady()) {
            onResult(true)
            return
        }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            android.widget.Toast.makeText(
                activity,
                "Shizuku 服务未启动，请先在 Shizuku App 中启动（adb 或 Root）",
                android.widget.Toast.LENGTH_LONG
            ).show()
            onResult(false)
            return
        }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE_PERMISSION) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        runCatching { Shizuku.requestPermission(REQUEST_CODE_PERMISSION) }
    }

    // ------------ 核心：一键开启无障碍服务 ------------

    private val targetComponent by lazy {
        "${context.packageName}/com.nya.app.service.NyaAccessibilityService"
    }

    fun enableAccessibilityServiceByShizuku(): Boolean {
        val svc = ensureShellService() ?: run {
            Log.w(TAG, "enableA11y: 无法连接到 UserService（Shizuku 未就绪？）")
            return false
        }
        return runCatching {
            // 保留用户原来已启用的无障碍服务，叠加我们的，避免把用户其他无障碍关掉
            val current = try {
                svc.execCommand("settings get secure enabled_accessibility_services")
                    .trim().trim('\n', ' ')
            } catch (_: Throwable) { "" }

            val merged = if (current.isBlank() || current == "null") {
                targetComponent
            } else {
                // 先移除本 App 旧条目再追加，避免重复
                val others = current.split(':')
                    .filter { it.isNotBlank() && !it.startsWith(context.packageName + "/", ignoreCase = true) }
                (others + targetComponent).joinToString(":")
            }

            val r1 = svc.execCommand("settings put secure enabled_accessibility_services \"$merged\"")
            val r2 = svc.execCommand("settings put secure accessibility_enabled 1")
            Log.i(TAG, "enableA11y result: putList=[$r1] putEnabled=[$r2], merged=[$merged]")
            true
        }.getOrElse {
            Log.e(TAG, "enableA11y failed", it)
            false
        }
    }

    // ------------ 工具：检查无障碍是否已开启 ------------

    fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = runCatching {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }.getOrDefault(false)
        if (!enabled) return false
        val services = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return services.split(':').any { it.equals(targetComponent, ignoreCase = true) }
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
