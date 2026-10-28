package com.im.nativeapp.ui.chat

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.im.nativeapp.core.media.VoicePlayer
import com.im.nativeapp.core.media.VoiceRecorder
import com.im.nativeapp.core.network.MessageVO
import com.im.nativeapp.ui.components.Avatar
import com.im.nativeapp.ui.components.formatBubbleTime
import com.im.nativeapp.ui.theme.WxColors
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 聊天页（对齐 Chat.vue + ChatWindow）：文字/图片/语音/文件、历史分页、撤回、回执、失败重发、引用回复、群@提醒 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenGroupManage: (String) -> Unit = {},
    vm: ChatViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val messages by vm.messageList.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val sendingAttachment by vm.sendingAttachment.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 拨打前补权限：语音要麦，视频要麦+摄像头
    var pendingCallType by remember { mutableStateOf<String?>(null) }
    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            vm.dialCall(pendingCallType ?: "audio")
        } else {
            Toast.makeText(context, "未授予麦克风/摄像头权限，无法拨打", Toast.LENGTH_SHORT).show()
        }
        pendingCallType = null
    }
    fun dial(type: String) {
        pendingCallType = type
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (type == "video") add(Manifest.permission.CAMERA)
        }
        callPermissionLauncher.launch(wanted.toTypedArray())
    }

    // toast 事件
    LaunchedEffect(Unit) {
        vm.toast.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    // 群聊：拉一次成员列表（@ 选人面板与提及解析共用）
    LaunchedEffect(conversation?.type) { vm.loadMembersIfGroup() }

    // 滚动到顶部（reverseLayout 下是列表尾部 index）时加载更早历史
    LaunchedEffect(listState, hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastIndex ->
                if (hasMore && lastIndex != null && lastIndex >= listState.layoutInfo.totalItemsCount - 3) {
                    vm.loadMore()
                }
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
                title = {
                    Column {
                        Text(conversation?.name ?: "聊天", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val subtitle = when {
                            conversation?.type == 2 -> "${conversation?.memberCount ?: 0} 人"
                            conversation?.online == true -> "在线"
                            else -> null
                        }
                        if (subtitle != null) {
                            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                },
                actions = {
                    // 单聊才有通话入口（对齐 Chat.vue 的 actions）
                    if (conversation?.type == 1) {
                        IconButton(onClick = { dial("audio") }) {
                            Icon(Icons.Default.Call, contentDescription = "语音通话")
                        }
                        IconButton(onClick = { dial("video") }) {
                            Icon(Icons.Default.Videocam, contentDescription = "视频通话")
                        }
                    }
                    // 群聊：群管理入口（成员/公告/群昵称，对齐 Web 端 GroupSettings）
                    if (conversation?.type == 2) {
                        IconButton(onClick = { conversation?.targetId?.let(onOpenGroupManage) }) {
                            Icon(Icons.Default.Settings, contentDescription = "群管理")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            Box(modifier = Modifier.weight(1f)) {
                // 微信聊天页底色：浅灰 #EDEDED（深色 #111111），气泡在灰底上才有层次
                val chatBg = if (androidx.compose.foundation.isSystemInDarkTheme()) WxColors.ChatBgDark else WxColors.ChatBg
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize().background(chatBg),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // reverseLayout：index 0 是视觉底部（最新消息），列表本身按 seq 升序，直接反转传入
                    items(messages.asReversed(), key = { it.messageId ?: it.clientMsgId ?: it.hashCode().toString() }) { msg ->
                        MessageRow(
                            msg = msg,
                            vm = vm,
                            onReply = { vm.setReply(msg) },
                            onRetry = { vm.resend(msg) },
                            onRecall = { vm.recall(msg) },
                            onDelete = { vm.deleteMessage(msg) },
                            onDiscard = { vm.discard(msg) }
                        )
                    }
                    if (hasMore) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
                if (sendingAttachment) {
                    LinearUploadingHint(modifier = Modifier.align(Alignment.TopCenter))
                }
            }

            ChatInputBar(vm = vm)
        }
    }
}

@Composable
private fun LinearUploadingHint(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(top = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            "附件上传中…",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

// ============================== 消息气泡 ==============================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    msg: MessageVO,
    vm: ChatViewModel,
    onReply: () -> Unit,
    onRetry: () -> Unit,
    onRecall: () -> Unit,
    onDelete: () -> Unit,
    onDiscard: () -> Unit
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf<String?>(null) }
    var showOcrText by remember { mutableStateOf(false) }
    val playingId by vm.voicePlayingId.collectAsStateWithLifecycle()

    // 已撤回与系统通知：居中灰字
    if (msg.recalled == true || msg.msgType == 5) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                if (msg.msgType == 5) msg.content ?: "" else recallText(msg),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        return
    }

    // 通话记录消息（msgType 6）：居中一条「语音通话 05:30」样式的灰字
    if (msg.msgType == 6) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    callRecordText(msg),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        return
    }

    val self = msg.self == true
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (self) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!self) {
            Avatar(url = vm.resolveUrl(msg.fromAvatar), name = msg.fromNickname ?: "?", size = 36.dp)
            Spacer(Modifier.width(8.dp))
        }

        Column(horizontalAlignment = if (self) Alignment.End else Alignment.Start) {
            if (!self) {
                Text(
                    msg.fromNickname ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(2.dp))
            }

            Box {
                // 微信气泡：自己浅绿 #95EC69 / 对方白底，四角均等小圆角，黑字
                val dark = androidx.compose.foundation.isSystemInDarkTheme()
                val bubbleColor = if (self) {
                    if (dark) WxColors.BubbleSelfDark else WxColors.BubbleSelf
                } else {
                    if (dark) WxColors.BubblePeerDark else WxColors.BubblePeer
                }
                val contentColor = if (dark) WxColors.OnBubbleDark else WxColors.OnBubble
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = bubbleColor,
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { menuOpen = true }
                    )
                ) {
                    BubbleContent(
                        msg = msg,
                        vm = vm,
                        contentColor = contentColor,
                        isPlaying = playingId == (msg.messageId ?: msg.clientMsgId),
                        onPreviewImage = { previewImage = it }
                    )
                }

                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // 回复：已落库且未撤回的普通消息可引用（对齐 ChatWindow 的 reply 菜单）
                    if (msg.messageId != null && msg.recalled != true) {
                        DropdownMenuItem(text = { Text("回复") }, onClick = { menuOpen = false; onReply() })
                    }
                    // 图内文字：发送端 OCR 的识别结果（已过敏感词），有才给入口（对齐 Web 端 ocr-text 菜单）
                    if (msg.msgType == 2 && !msg.extra?.ocrText.isNullOrBlank()) {
                        DropdownMenuItem(text = { Text("图内文字") }, onClick = { menuOpen = false; showOcrText = true })
                    }
                    if (msg.status == 5) {
                        DropdownMenuItem(text = { Text("重新发送") }, onClick = { menuOpen = false; onRetry() })
                        DropdownMenuItem(text = { Text("删除草稿") }, onClick = { menuOpen = false; onDiscard() })
                    }
                    if (self && msg.messageId != null) {
                        DropdownMenuItem(text = { Text("撤回") }, onClick = { menuOpen = false; onRecall() })
                    }
                    if (msg.messageId != null) {
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (msg.status == 5) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = "发送失败",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp).clickable { onRetry() }
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("发送失败，点击重试", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        formatBubbleTime(msg.sendTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    if (self) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            statusText(msg.status),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }

        if (self) {
            Spacer(Modifier.width(8.dp))
            Avatar(url = vm.resolveUrl(msg.fromAvatar), name = msg.fromNickname ?: "我", size = 36.dp)
        }
    }

    // 图片全屏预览
    previewImage?.let { url ->
        Dialog(onDismissRequest = { previewImage = null }) {
            AsyncImage(
                model = url,
                contentDescription = "预览",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { previewImage = null },
                contentScale = ContentScale.Fit
            )
        }
    }

    // 图内文字弹窗（由发送端自动识别，仅供参考）
    if (showOcrText) {
        Dialog(onDismissRequest = { showOcrText = false }) {
            Surface(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "图内文字（由发送端自动识别，仅供参考）",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        msg.extra?.ocrText ?: "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "关闭",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.align(Alignment.End).clickable { showOcrText = false }
                    )
                }
            }
        }
    }
}

private fun recallText(msg: MessageVO): String =
    if (msg.self == true) "你撤回了一条消息" else "${msg.fromNickname ?: "对方"} 撤回了一条消息"

private fun statusText(status: Int?): String = when (status) {
    0 -> "发送中"
    1 -> "已发送"
    2 -> "已送达"
    3 -> "已读"
    else -> ""
}

@Composable
private fun BubbleContent(
    msg: MessageVO,
    vm: ChatViewModel,
    contentColor: Color,
    isPlaying: Boolean,
    onPreviewImage: (String) -> Unit
) {
    Column {
        // 引用块：只认 message.quote（占位阶段本地拼、回包服务端权威覆盖，对齐 ChatWindow）
        msg.quote?.let { q ->
            val qRecalled = q["recalled"]?.jsonPrimitive?.contentOrNull == "true"
            val qNick = q["fromNickname"]?.jsonPrimitive?.contentOrNull ?: ""
            val qContent = if (qRecalled) "[引用内容已撤回]" else q["content"]?.jsonPrimitive?.contentOrNull ?: ""
            if (qContent.isNotEmpty()) {
                Text(
                    "$qNick: $qContent",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.65f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 8.dp, end = 8.dp, top = 6.dp)
                        .background(contentColor.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
        when (msg.msgType) {
        2 -> {
            // 图片：宽高分按照 extra.width/height，未带时给 4:3 兜底
            val url = vm.resolveUrl(msg.extra?.fileUrl)
            val w = msg.extra?.width ?: 4
            val h = msg.extra?.height ?: 3
            val maxWidth = 220.dp
            val height = maxWidth * h.toFloat() / w.toFloat()
            AsyncImage(
                model = url,
                contentDescription = "图片",
                modifier = Modifier
                    .width(maxWidth)
                    .height(height.coerceIn(80.dp, 320.dp))
                    .clickable { url?.let(onPreviewImage) },
                contentScale = ContentScale.Crop
            )
        }
        3 -> {
            // 文件卡片：点击换票据直链交给系统下载管理器（M2 下载管理）
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .widthIn(max = 240.dp)
                    .clickable { vm.downloadFile(msg) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null, tint = contentColor)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        msg.extra?.fileName ?: "文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        buildString {
                            append(formatSize(msg.extra?.fileSize))
                            if (isNotEmpty()) append(" · ")
                            append("点击下载")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                }
            }
        }
        4 -> {
            // 语音条：宽度随时长，点击播放
            val duration = msg.extra?.duration ?: 0
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .width((60 + duration * 4).coerceAtMost(200).dp)
                    .clickable {
                        val url = vm.resolveUrl(msg.extra?.fileUrl)
                        if (url != null) vm.playVoice(msg.messageId ?: msg.clientMsgId ?: "", url)
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "播放",
                    tint = if (isPlaying) MaterialTheme.colorScheme.primary else contentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("$duration″", style = MaterialTheme.typography.bodyMedium, color = contentColor)
            }
        }
        else -> {
            Text(
                msg.content ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        }
    }
}

private fun formatSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return ""
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
    return "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/** 通话记录气泡文案：语音通话 05:30 / 视频通话 未接通 */
private fun callRecordText(msg: MessageVO): String {
    val type = if (msg.extra?.callMediaType == "video") "视频通话" else "语音通话"
    val duration = msg.extra?.duration ?: 0
    return if (duration > 0) {
        val m = duration / 60
        val s = duration % 60
        "$type %02d:%02d".format(m, s)
    } else {
        "$type 未接通"
    }
}

// ============================== 输入栏 ==============================

@Composable
private fun ChatInputBar(vm: ChatViewModel) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var voiceMode by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    val replyTarget by vm.replyTarget.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val isGroup = vm.conversation.collectAsStateWithLifecycle().value?.type == 2

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.sendImage(uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.sendFile(uri)
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "需要录音权限才能发送语音", Toast.LENGTH_SHORT).show()
    }

    // @ 提及面板（对齐 ChatWindow watch(draft)）：取最后一个 @ 后的文本做过滤词，含空白即关闭
    val mentionQuery = if (!isGroup) null else {
        val lastAt = text.lastIndexOf('@')
        if (lastAt < 0) null else {
            val afterAt = text.substring(lastAt + 1)
            if (afterAt.any { it.isWhitespace() }) null else afterAt
        }
    }

    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        // 回复横幅（对齐 ChatWindow 的 reply-banner）：发送或点 × 后消失
        replyTarget?.let { target ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "回复 ${target.fromNickname ?: ""}：${vm.quoteSummaryText(target)}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    Icons.Default.Close,
                    contentDescription = "取消回复",
                    modifier = Modifier.size(16.dp).clickable { vm.cancelReply() },
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }

        // @ 选人面板：过滤成员（排除自己），点选把 @昵称 写回草稿
        if (mentionQuery != null) {
            val kw = mentionQuery.lowercase()
            val candidates = members.filter { m ->
                m.userId != vm.myUserId &&
                    (kw.isEmpty() || (m.nicknameInGroup ?: m.displayName ?: m.nickname ?: "").lowercase().contains(kw))
            }
            val showAll = "所有人".contains(mentionQuery)
            if (candidates.isNotEmpty() || showAll) {
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                    if (showAll) {
                        item(key = "__all") {
                            MentionRow(name = "所有人", subtitle = "提醒全部成员", avatarUrl = null, vm = vm) {
                                val lastAt = text.lastIndexOf('@')
                                text = text.substring(0, lastAt) + "@所有人 "
                            }
                        }
                    }
                    items(candidates.take(20), key = { it.userId ?: it.hashCode().toString() }) { m ->
                        val name = m.nicknameInGroup ?: m.displayName ?: m.nickname ?: "成员"
                        MentionRow(name = name, subtitle = m.roleDesc, avatarUrl = vm.resolveUrl(m.avatar), vm = vm) {
                            val lastAt = text.lastIndexOf('@')
                            text = text.substring(0, lastAt) + "@$name "
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(onClick = {
                if (!voiceMode) audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                voiceMode = !voiceMode
            }) {
                Icon(if (voiceMode) Icons.Default.Keyboard else Icons.Default.Mic, contentDescription = "语音/键盘切换")
            }

            if (voiceMode) {
                // 按住说话（对齐移动 IM 惯例）：按下录音，松开发送，不足 1 秒丢弃
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(
                            if (recording) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp)
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    if (!recording) {
                                        val path = vm.recorder.start()
                                        if (path != null) recording = true
                                    }
                                    tryAwaitRelease()
                                    if (recording) {
                                        recording = false
                                        val result = vm.recorder.stop()
                                        if (result != null) vm.sendVoice(result.first, result.second)
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (recording) "松开 发送" else "按住 说话", fontWeight = FontWeight.Medium)
                }
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f).heightIn(max = 120.dp),
                    placeholder = { Text("发送消息…") },
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp)
                )
            }

            IconButton(onClick = { panelOpen = !panelOpen }) {
                Icon(Icons.Default.Add, contentDescription = "更多")
            }
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        vm.sendText(text)
                        text = ""
                    }
                },
                enabled = text.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }

        if (panelOpen) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                PanelEntry(icon = { Icon(Icons.Default.Image, contentDescription = null) }, label = "相册") {
                    panelOpen = false
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                PanelEntry(icon = { Icon(Icons.Default.AttachFile, contentDescription = null) }, label = "文件") {
                    panelOpen = false
                    filePicker.launch(arrayOf("*/*"))
                }
            }
        }
    }
}

/** @ 选人面板的成员行（对齐 ChatWindow mention 面板项） */
@Composable
private fun MentionRow(name: String, subtitle: String?, avatarUrl: String?, vm: ChatViewModel, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(url = avatarUrl, name = name, size = 28.dp)
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun PanelEntry(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
