package org.autojs.autojs.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.autojs.autojs.ui.main.MainActivity
import org.autojs.autoxjs.R

/**
 * MCP 服务保活前台服务.
 *
 * 只负责常驻通知，不持有监听引擎——引擎生命周期仍由 [McpServer] 管理，
 * 二者由 [McpServer.start]/[McpServer.stop] 成对启停.
 */
class McpForegroundService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "org.autojs.autojs.mcp.foreground"
        private const val ACTION_STOP = "org.autojs.autojs.mcp.action.FOREGROUND_STOP"

        /** 失败不抛：保活是尽力而为，服务本身照常运行 */
        fun start(context: Context?) {
            context ?: return
            runCatching {
                val intent = Intent(context, McpForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context?) {
            context ?: return
            runCatching { context.stopService(Intent(context, McpForegroundService::class.java)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            McpServer.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(true)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, McpForegroundService::class.java).setAction(ACTION_STOP),
            flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.text_mcp_foreground_title))
            .setContentText(
                getString(R.string.text_mcp_foreground_text, "${McpConfig.host}:${McpConfig.port}")
            )
            .setSmallIcon(R.drawable.autojs_logo)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(contentIntent)
            .setChannelId(CHANNEL_ID)
            .setVibrate(longArrayOf(0))
            .addAction(0, getString(R.string.text_mcp_foreground_stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.text_mcp_foreground_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }
}
