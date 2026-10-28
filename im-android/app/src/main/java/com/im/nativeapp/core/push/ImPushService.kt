package com.im.nativeapp.core.push

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.im.nativeapp.MainActivity
import com.im.nativeapp.R
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.ws.ImWebSocket
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 消息保活前台服务：局域网部署没有 FCM，进程被回收后 WS 断开就再也收不到消息。
 * 常驻一条低重要性通知换进程存活（dataSync 类型），WS 重连逻辑仍在 ImWebSocket 里，
 * 服务只负责「让进程活着 + 确保 connect 被调过」。
 */
@AndroidEntryPoint
class ImPushService : Service() {

    @Inject lateinit var ws: ImWebSocket
    @Inject lateinit var notifier: MessageNotifier
    @Inject lateinit var session: SessionStore

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, MessageNotifier.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("消息服务运行中")
            .setContentText("保持连接以接收新消息")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        // API 34+ 用 specialUse：Android 15 起 dataSync 前台服务累计满 6 小时会被系统强停，
        // IM 长连接必须全天在线，只能换类型（specialUse 无时长限额）
        val fgsType = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, MessageNotifier.FOREGROUND_ID, notification, fgsType)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (session.isLoggedIn()) ws.connect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
