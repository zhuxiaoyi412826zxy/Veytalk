package com.im.nativeapp.ui.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.im.nativeapp.ui.friend.FriendTab
import com.im.nativeapp.ui.live.LiveTab
import com.im.nativeapp.ui.profile.ProfileTab

/**
 * 登录后的主容器：底部四 Tab（消息 / 好友 / 直播 / 我的）。
 * 群聊并入好友页内双板块切换（对齐 Web 端 FriendList 的 好友|群聊 tabs）；
 * 手机端只观看不开播，直播页对齐 Web /live 的 直播|视频 双板块。
 * 各 Tab 内容保活：用 when 切换会把离开的组合销毁，这里用「全部常驻 + 显隐」保住滚动位置与输入态。
 */
@Composable
fun MainScreen(
    onOpenChat: (String) -> Unit,
    onOpenFriendRequests: () -> Unit,
    onOpenAddFriend: () -> Unit,
    onOpenCreateGroup: () -> Unit,
    onOpenLiveRoom: (String) -> Unit,
    onOpenVideoPlayer: (com.im.nativeapp.core.network.VideoVO) -> Unit = {},
    onOpenAi: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onLogout: () -> Unit,
    vm: MainViewModel = hiltViewModel()
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val totalUnread by vm.totalUnread.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()

    // 登录态被服务端判定失效（1002/2010）→ 回登录页
    LaunchedEffect(Unit) {
        vm.forceLogin.collect { onLogout() }
    }

    // 微信导航样式：无 pill 指示器，图标文字选中变绿
    val navItemColors = androidx.compose.material3.NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = androidx.compose.ui.graphics.Color.Transparent
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    colors = navItemColors,
                    icon = {
                        BadgedBox(badge = { if (totalUnread > 0) Badge { Text(if (totalUnread > 99) "99+" else totalUnread.toString()) } }) {
                            Icon(Icons.Default.Forum, contentDescription = "消息")
                        }
                    },
                    label = { Text("消息") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    colors = navItemColors,
                    icon = {
                        BadgedBox(badge = { if (pendingCount > 0) Badge { Text(pendingCount.toString()) } }) {
                            Icon(Icons.Default.Person, contentDescription = "好友")
                        }
                    },
                    label = { Text("好友") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    colors = navItemColors,
                    icon = { Icon(Icons.Default.PlayCircle, contentDescription = "视频") },
                    label = { Text("视频") }
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    colors = navItemColors,
                    icon = { Icon(Icons.Default.AccountCircle, contentDescription = "我的") },
                    label = { Text("我的") }
                )
            }
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
            when (tab) {
                0 -> ConversationTab(onOpenChat = onOpenChat)
                1 -> FriendTab(
                    onOpenChat = onOpenChat,
                    onOpenRequests = onOpenFriendRequests,
                    onOpenAddFriend = onOpenAddFriend,
                    onOpenCreateGroup = onOpenCreateGroup
                )
                2 -> LiveTab(onOpenRoom = onOpenLiveRoom, onOpenVideoPlayer = onOpenVideoPlayer)
                3 -> ProfileTab(onLogout = onLogout, onOpenAi = onOpenAi, onOpenProfile = onOpenProfile)
            }
        }
    }
}
