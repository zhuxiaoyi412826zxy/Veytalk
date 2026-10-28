package com.im.nativeapp.ui.group

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.core.network.ApiError
import com.im.nativeapp.core.network.GroupAddMembersRequest
import com.im.nativeapp.core.network.GroupApi
import com.im.nativeapp.core.network.GroupMemberVO
import com.im.nativeapp.core.network.GroupMyNicknameRequest
import com.im.nativeapp.core.network.GroupUpdateRequest
import com.im.nativeapp.core.network.GroupVO
import com.im.nativeapp.core.network.unwrap
import com.im.nativeapp.core.network.unwrapUnit
import com.im.nativeapp.data.ConversationRepository
import com.im.nativeapp.data.FriendRepository
import com.im.nativeapp.ui.components.Avatar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import javax.inject.Inject

/**
 * 群管理（Web 端 GroupSettings 面板的原生化）：
 * 成员列表 / 踢人 / 拉人 / 改公告 / 我的群昵称。
 * 权限口径与后端一致：群主可移除任何非群主，管理员只能移除普通成员；公告仅管理层可改。
 */
@HiltViewModel
class GroupManageViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val apiClient: ApiClient,
    private val session: SessionStore,
    private val friendRepo: FriendRepository,
    private val conversations: ConversationRepository
) : ViewModel() {

    val groupId: String = checkNotNull(savedStateHandle["groupId"])

    private val _detail = MutableStateFlow<GroupVO?>(null)
    val detail: StateFlow<GroupVO?> = _detail

    private val _members = MutableStateFlow<List<GroupMemberVO>>(emptyList())
    val members: StateFlow<List<GroupMemberVO>> = _members

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast
    fun consumeToast() { _toast.value = null }

    /** 好友列表原样透传：拉人弹窗的数据源（已在群内的前端过滤，后端也会跳过） */
    val friends = friendRepo.friends

    private val api get() = apiClient.service(GroupApi::class.java)

    fun resolveUrl(path: String?): String? = session.resolveUrl(path)

    /** 管理层（群主/管理员）才能改公告、踢人 */
    fun canManage(): Boolean = (_detail.value?.myRole ?: 3) in listOf(1, 2)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val d: GroupVO = api.detail(groupId).unwrap(apiClient.json)
                _detail.value = d
                val envelope = api.members(groupId, current = 1, size = 200)
                if (envelope.code == 200) {
                    val records = (envelope.data as? JsonObject)?.get("records") as? JsonArray
                    _members.value = records?.mapNotNull {
                        runCatching { apiClient.json.decodeFromJsonElement<GroupMemberVO>(it) }.getOrNull()
                    } ?: emptyList()
                }
                runCatching { friendRepo.fetchFriends() }
            } catch (e: Exception) {
                _toast.value = if (e is ApiError) e.message else "加载失败"
            } finally {
                _loading.value = false
            }
        }
    }

    /** 改公告：传空串表示清空（对齐 group.js updateGroup 的字段语义） */
    fun updateNotice(notice: String) {
        mutate("公告已更新") { api.update(groupId, GroupUpdateRequest(notice = notice)).unwrapUnit() }
    }

    /** 我的群昵称：空串清除，回退展示账号昵称 */
    fun updateMyNickname(nickname: String) {
        mutate("群昵称已更新") { api.updateMyNickname(groupId, GroupMyNicknameRequest(nickname)).unwrapUnit() }
    }

    /** 踢人：后端还会做权限校验，这里只管调用与刷新 */
    fun removeMember(member: GroupMemberVO) {
        val uid = member.userId ?: return
        mutate("已移除 ${member.displayName ?: member.nickname ?: ""}") {
            api.removeMember(groupId, uid).unwrapUnit()
        }
    }

    /** 拉人：已在群内的后端跳过，返回实际新增列表 */
    fun addMembers(userIds: List<String>) {
        if (userIds.isEmpty()) return
        mutate("已邀请 ${userIds.size} 人入群") { api.addMembers(groupId, GroupAddMembersRequest(userIds)).unwrapUnit() }
    }

    private fun mutate(successTip: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _toast.value = successTip
                refresh()
                // 群资料/成员数变了，聊天页顶栏与会话列表一起刷
                runCatching { conversations.fetchList() }
            } catch (e: Exception) {
                _toast.value = if (e is ApiError) e.message else "操作失败"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupManageScreen(onBack: () -> Unit, vm: GroupManageViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val detail by vm.detail.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val friends by vm.friends.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()

    var editNotice by remember { mutableStateOf(false) }
    var editNickname by remember { mutableStateOf(false) }
    var addMember by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("群管理") }
            )
        }
    ) { padding ->
        if (loading && detail == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 群公告
            item(key = "notice") {
                SectionTitle("群公告")
                Surface(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = vm.canManage()) { editNotice = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            detail?.notice?.ifBlank { "未设置" } ?: "未设置",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (detail?.notice.isNullOrBlank()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (vm.canManage()) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            // 成员
            item(key = "member-head") {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle("群成员 ${members.size} 人", modifier = Modifier.weight(1f))
                    Text(
                        "邀请",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable { addMember = true }
                    )
                }
            }
            items(members, key = { it.userId ?: it.hashCode().toString() }) { m ->
                MemberRow(
                    member = m,
                    avatarUrl = vm.resolveUrl(m.avatar),
                    removable = canRemove(vm.detail.value?.myRole, m),
                    onRemove = { vm.removeMember(m) }
                )
            }

            // 我的群昵称
            item(key = "my-nickname") {
                Spacer(Modifier.height(12.dp))
                SectionTitle("我的群昵称")
                Surface(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editNickname = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            detail?.myNickname?.ifBlank { "未设置（显示账号昵称）" } ?: "未设置（显示账号昵称）",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (detail?.myNickname.isNullOrBlank()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }

    if (editNotice) {
        TextEditDialog(
            title = "群公告",
            initial = detail?.notice ?: "",
            maxLength = 500,
            onDismiss = { editNotice = false },
            onConfirm = { vm.updateNotice(it); editNotice = false }
        )
    }
    if (editNickname) {
        TextEditDialog(
            title = "我的群昵称",
            initial = detail?.myNickname ?: "",
            maxLength = 30,
            hint = "留空清除，回退显示账号昵称",
            onDismiss = { editNickname = false },
            onConfirm = { vm.updateMyNickname(it); editNickname = false }
        )
    }
    if (addMember) {
        AddMemberDialog(
            friends = friends.filter { f -> members.none { m -> m.userId == f.friendId } },
            resolveUrl = { vm.resolveUrl(it) },
            onDismiss = { addMember = false },
            onConfirm = { vm.addMembers(it); addMember = false }
        )
    }
}

/** 踢人权限（对齐后端注释）：群主可移除任何非群主；管理员只能移除普通成员 */
private fun canRemove(myRole: Int?, member: GroupMemberVO): Boolean {
    if (member.role == 1) return false
    return when (myRole) {
        1 -> true
        2 -> member.role == 3
        else -> false
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun MemberRow(member: GroupMemberVO, avatarUrl: String?, removable: Boolean, onRemove: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(url = avatarUrl, name = member.displayName ?: member.nickname ?: "?", size = 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    member.displayName ?: member.nickname ?: "成员",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                member.roleDesc?.takeIf { member.role != 3 }?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (member.muted == true) {
                Text("已被禁言", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (removable) {
            Text(
                "移除",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable { confirm = true }
            )
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("移除成员") },
            text = { Text("确定把 ${member.displayName ?: member.nickname ?: "该成员"} 移出群聊吗？") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onRemove() }) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
        )
    }
}

/** 单字段编辑弹窗：公告/群昵称共用 */
@Composable
private fun TextEditDialog(
    title: String,
    initial: String,
    maxLength: Int,
    hint: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= maxLength) value = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = if (maxLength > 60) 3 else 1
                )
                if (hint != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 拉人弹窗：好友多选（已在群内的不出现在候选里） */
@Composable
private fun AddMemberDialog(
    friends: List<com.im.nativeapp.core.network.FriendVO>,
    resolveUrl: (String?) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("邀请好友入群") },
        text = {
            if (friends.isEmpty()) {
                Text("没有可邀请的好友", color = MaterialTheme.colorScheme.secondary)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(friends, key = { it.friendId ?: it.username }) { f ->
                        val fid = f.friendId ?: return@items
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (selected.contains(fid)) selected - fid else selected + fid
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = selected.contains(fid), onCheckedChange = null)
                            Avatar(url = resolveUrl(f.avatar), name = f.displayName.ifEmpty { f.nickname }, size = 30.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                f.displayName.ifEmpty { f.nickname.ifEmpty { f.username } },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty(), onClick = { onConfirm(selected.toList()) }) {
                Text("邀请（${selected.size}）")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
