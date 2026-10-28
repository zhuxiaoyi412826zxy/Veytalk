package com.im.nativeapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.im.nativeapp.core.call.CallManager
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.config.SettingsStore
import com.im.nativeapp.core.push.ImPushService
import com.im.nativeapp.core.push.MessageNotifier
import com.im.nativeapp.data.WsDispatcher
import com.im.nativeapp.ui.call.CallOverlay
import com.im.nativeapp.ui.chat.ChatScreen
import com.im.nativeapp.ui.friend.AddFriendScreen
import com.im.nativeapp.ui.friend.FriendRequestsScreen
import com.im.nativeapp.ui.group.CreateGroupScreen
import com.im.nativeapp.ui.home.MainScreen
import com.im.nativeapp.ui.login.LoginScreen
import com.im.nativeapp.ui.live.LiveRoomScreen
import com.im.nativeapp.ui.theme.ImTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var session: SessionStore

    @Inject
    lateinit var settings: SettingsStore

    @Inject
    lateinit var wsDispatcher: WsDispatcher

    @Inject
    lateinit var callManager: CallManager

    @Inject
    lateinit var videoPlayerHolder: com.im.nativeapp.core.media.VideoPlayerHolder

    /** 通知点击进入的会话 ID：onCreate/onNewIntent 写入，NavHost 就绪后消费 */
    private val pendingOpenConv = mutableStateOf<String?>(null)

    // API 33+ 通知运行时权限：拒绝只是不弹系统通知，前台服务保活不受影响
    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 登录态冷启动：拉起保活服务（顺带补 WS connect，connect 幂等可多处调用）
        if (session.isLoggedIn()) {
            runCatching { ContextCompat.startForegroundService(this, Intent(this, ImPushService::class.java)) }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        pendingOpenConv.value = intent.getStringExtra("openConversation")
        setContent {
            // 主题用可变状态承接：「我的」里改完立即重排生效，不必重启
            var themeMode by remember { mutableStateOf(settings.themeMode) }
            // 设置页改完回写这个状态（SettingsStore 已是单一事实源，这里只做触发）
            LaunchedEffect(Unit) {
                com.im.nativeapp.ui.theme.ThemeEvents.changes.collect { themeMode = settings.themeMode }
            }
            ImTheme(themeMode = themeMode) {
                val nav = rememberNavController()
                // 冷启动：有 token 先进行为主页（各仓库首拉失败会由 1002/2010 回登录页）
                val start = if (session.isLoggedIn()) "main" else "login"

                // 被顶下线：弹提示并强制回登录页（对齐 dispatch.js 的 kickout 处理）
                LaunchedEffect(Unit) {
                    wsDispatcher.kickout.collect { reason ->
                        Toast.makeText(this@MainActivity, reason, Toast.LENGTH_LONG).show()
                        nav.navigate("login") { popUpTo(0) { inclusive = true } }
                    }
                }

                // 点系统通知跳进对应会话（singleTask 下冷启动走 onCreate，热启动走 onNewIntent）
                LaunchedEffect(pendingOpenConv.value) {
                    val cid = pendingOpenConv.value
                    if (!cid.isNullOrEmpty() && session.isLoggedIn()) {
                        pendingOpenConv.value = null
                        nav.navigate("chat/$cid")
                    }
                }

                Box(Modifier.fillMaxSize()) {
                NavHost(navController = nav, startDestination = start) {
                    composable("login") {
                        LoginScreen(
                            onLoginSuccess = {
                                nav.navigate("main") { popUpTo("login") { inclusive = true } }
                            }
                        )
                    }
                    composable("main") {
                        MainScreen(
                            onOpenChat = { cid -> nav.navigate("chat/$cid") },
                            onOpenFriendRequests = { nav.navigate("friend-requests") },
                            onOpenAddFriend = { nav.navigate("add-friend") },
                            onOpenCreateGroup = { nav.navigate("group-create") },
                            onOpenLiveRoom = { roomId -> nav.navigate("live/$roomId") },
                            onOpenVideoPlayer = { v ->
                                // 点击即预建播放器缓冲，导航转场期间完成首帧 → 无缝切入
                                videoPlayerHolder.prepare(v.fileUrl)
                                val url = java.net.URLEncoder.encode(v.fileUrl ?: "", "UTF-8")
                                val title = java.net.URLEncoder.encode(v.title ?: "视频", "UTF-8")
                                val cover = java.net.URLEncoder.encode(v.coverUrl ?: "", "UTF-8")
                                nav.navigate("video-player?url=$url&title=$title&cover=$cover&w=${v.width ?: 16}&h=${v.height ?: 9}")
                            },
                            onOpenAi = { nav.navigate("ai") },
                            onOpenProfile = { nav.navigate("profile-edit") },
                            onLogout = {
                                nav.navigate("login") { popUpTo("main") { inclusive = true } }
                            }
                        )
                    }
                    composable(
                        route = "chat/{conversationId}",
                        arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
                    ) {
                        ChatScreen(
                            onBack = { nav.popBackStack() },
                            onOpenGroupManage = { gid -> nav.navigate("group-manage/$gid") }
                        )
                    }
                    composable(
                        route = "group-manage/{groupId}",
                        arguments = listOf(navArgument("groupId") { type = NavType.StringType })
                    ) {
                        com.im.nativeapp.ui.group.GroupManageScreen(onBack = { nav.popBackStack() })
                    }
                    composable(
                        route = "live/{roomId}",
                        arguments = listOf(navArgument("roomId") { type = NavType.StringType })
                    ) {
                        LiveRoomScreen(roomId = it.arguments?.getString("roomId") ?: "", onBack = { nav.popBackStack() })
                    }
                    composable("friend-requests") {
                        FriendRequestsScreen(
                            onBack = { nav.popBackStack() },
                            onOpenChat = { cid ->
                                nav.navigate("chat/$cid") { popUpTo("main") }
                            }
                        )
                    }
                    composable("add-friend") {
                        AddFriendScreen(onBack = { nav.popBackStack() })
                    }
                    composable("ai") {
                        com.im.nativeapp.ui.ai.AiScreen(onBack = { nav.popBackStack() })
                    }
                    composable(
                        route = "video-player?url={url}&title={title}&cover={cover}&w={w}&h={h}",
                        arguments = listOf(
                            navArgument("url") { type = NavType.StringType; defaultValue = "" },
                            navArgument("title") { type = NavType.StringType; defaultValue = "" },
                            navArgument("cover") { type = NavType.StringType; defaultValue = "" },
                            navArgument("w") { type = NavType.IntType; defaultValue = 16 },
                            navArgument("h") { type = NavType.IntType; defaultValue = 9 }
                        )
                    ) {
                        com.im.nativeapp.ui.live.VideoPlayerScreen(onBack = { nav.popBackStack() })
                    }
                    composable("profile-edit") {
                        com.im.nativeapp.ui.profile.ProfileEditScreen(onBack = { nav.popBackStack() })
                    }
                    composable("group-create") {
                        CreateGroupScreen(
                            onBack = { nav.popBackStack() },
                            onOpenChat = { cid ->
                                nav.navigate("chat/$cid") { popUpTo("main") }
                            }
                        )
                    }
                }

                // 通话浮层：盖在一切路由之上，CallManager 状态驱动显隐
                CallOverlay(callManager)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("openConversation")?.let { pendingOpenConv.value = it }
    }

    // 前台静默（对齐 Web 端：App 可见时不弹系统通知）
    override fun onResume() {
        super.onResume()
        MessageNotifier.appForeground = true
    }

    override fun onPause() {
        super.onPause()
        MessageNotifier.appForeground = false
    }
}
