package com.im.nativeapp.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.data.AuthRepository
import com.im.nativeapp.ui.components.Avatar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val session: SessionStore
) : ViewModel() {
    val user = auth.currentUser
    fun resolveUrl(path: String?): String? = session.resolveUrl(path)
}

/**
 * 「我」页（对齐微信）：资料卡 + 设置入口。
 * 所有具体设置在独立的 Settings 页里，按微信的分组列表组织。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTab(
    onOpenSettings: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    vm: ProfileViewModel = hiltViewModel()
) {
    val user by vm.user.collectAsStateWithLifecycle()
    var aboutDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenProfile)
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(
                        url = vm.resolveUrl(user.avatar),
                        name = user.nickname.ifEmpty { user.username },
                        size = 58.dp
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            user.nickname.ifEmpty { user.username },
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            "@${user.username}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ProfileRow("设置", onClick = onOpenSettings)
                    HorizontalDivider()
                    ProfileRow("关于 IM", onClick = { aboutDialog = true })
                    HorizontalDivider()
                    // 开发调试：开启后全局右上角实时显示日志，结束后询问是否导出
                    ProfileRow("开发调试", onClick = {
                        if (com.im.nativeapp.core.log.AppLog.running.value) {
                            android.widget.Toast.makeText(context, "调试日志正在运行，右上角点「结束」可停止", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            com.im.nativeapp.core.log.AppLog.start()
                            android.widget.Toast.makeText(context, "已开启，右上角实时显示日志", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (aboutDialog) {
        AlertDialog(
            onDismissRequest = { aboutDialog = false },
            title = { Text("关于 IM") },
            text = {
                Text("版本 $versionName\n局域网即时通讯 · 文件传输 · 音视频通话")
            },
            confirmButton = { TextButton(onClick = { aboutDialog = false }) { Text("知道了") } }
        )
    }
}

/** 设置列表行（微信风：白底、16dp 左右内边距、右侧箭头） */
@Composable
private fun ProfileRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
    }
}
