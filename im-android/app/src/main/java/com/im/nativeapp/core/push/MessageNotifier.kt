package com.im.nativeapp.core.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.im.nativeapp.MainActivity
import com.im.nativeapp.R
import com.im.nativeapp.core.network.MessageVO
import com.im.nativeapp.data.ConversationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 新消息系统通知（局域网场景没有 FCM 可用，靠前台服务保活 WS 后本地发通知）。
 *
 * 策略（对齐 Web 端 utils/notify 的语义）：
 * - 正在看这个会话 → 不通知（角标都没有，弹通知只是打扰）
 * - 免打扰会话 → 静默；但被 @ 的消息穿透免打扰（与后端 at_flag 的设计一致）
 * - App 在前台 → 不弹系统通知（Web 端前台静默同口径）
 * - 按会话聚合：同一会话的新通知覆盖旧的，通知栏不被刷爆
 */
@Singleton
class MessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversations: ConversationRepository
) {
    companion object {
        const val CHANNEL_MESSAGE = "im_message"
        const val CHANNEL_SERVICE = "im_service"
        const val FOREGROUND_ID = 1001

        /** App 是否在前台：MainActivity onResume/onPause 维护（前台静默，对齐 Web 端） */
        @Volatile var appForeground = false
    }

    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGE, "消息通知", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "后台连接", NotificationManager.IMPORTANCE_MIN).apply {
                description = "保持与服务器的连接以接收消息"
                setShowBadge(false)
            }
        )
    }

    fun onIncoming(dto: MessageVO, viewing: Boolean, mentioned: Boolean) {
        if (viewing || appForeground) return
        val cid = dto.conversationId ?: return
        val conv = conversations.find(cid)
        if (conv?.muted == true && !mentioned) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannels()
        val title = conv?.name ?: dto.fromNickname ?: "新消息"
        val summary = ConversationRepository.summarize(dto)
        // 群聊正文带发送者（标题已是群名），单聊标题就是对方本人，正文不再重复
        val body = if (conv?.type == 2) "${dto.fromNickname ?: ""}: $summary" else summary

        val intent = Intent(context, MainActivity::class.java)
            .putExtra("openConversation", cid)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, cid.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(cid.hashCode(), notification) }
    }
}
