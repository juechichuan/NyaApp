package com.nya.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nya.app.NyaApplication
import com.nya.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "NyaForegroundSvc"
private const val CHANNEL_ID = "nya_toggle_channel"
private const val NOTIFICATION_ID = 9527

class NyaForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        const val ACTION_TOGGLE = "com.nya.app.ACTION_TOGGLE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                val prefs = (application as NyaApplication).prefs
                val current = prefs.snapshotBlocking().masterEnabled
                scope.launch {
                    prefs.setMasterEnabled(!current)
                }
            }
        }

        // 启动前台服务并显示通知
        val prefs = (application as NyaApplication).prefs
        val enabled = prefs.snapshotBlocking().masterEnabled
        startForeground(NOTIFICATION_ID, buildNotification(enabled))

        // 监听 masterEnabled 变化，实时更新通知
        scope.launch {
            (application as NyaApplication).prefs.masterEnabled.collectLatest { enabled ->
                withContext(Dispatchers.Main) {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification(enabled))
                }
            }
        }

        return START_STICKY
    }

    private fun buildNotification(masterEnabled: Boolean): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 切换按钮
        val toggleIntent = Intent(this, NyaForegroundService::class.java).apply {
            action = ACTION_TOGGLE
        }
        val togglePending = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (masterEnabled) "🐱 自动追加：已开启" else "🐱 自动追加：已关闭"
        val text = if (masterEnabled) "点击下方按钮快速关闭" else "点击下方按钮快速开启"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tapPending)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                if (masterEnabled) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                if (masterEnabled) "关闭自动追加" else "开启自动追加",
                togglePending
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "喵输入法快捷开关",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "常驻通知，用于快捷开关自动追加功能"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Log.i(TAG, "NyaForegroundService destroyed")
    }
}
