@file:OptIn(coil.annotation.ExperimentalCoilApi::class)

package com.im.nativeapp.ui.profile

import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.Coil
import coil.compose.AsyncImage
import com.im.nativeapp.ImApp
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.config.SettingsStore
import com.im.nativeapp.core.config.UiPrefs
import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.core.network.ApiError
import com.im.nativeapp.core.network.CancelAccountRequest
import com.im.nativeapp.core.network.ChangePasswordRequest
import com.im.nativeapp.core.network.ConversationVO
import com.im.nativeapp.core.network.MessageApi
import com.im.nativeapp.core.network.UserApi
import com.im.nativeapp.core.network.unwrapUnit
import com.im.nativeapp.data.AuthRepository
import com.im.nativeapp.data.ConversationRepository
import com.im.nativeapp.data.MessageRepository
import com.im.nativeapp.ui.theme.AccentPresets
import com.im.nativeapp.ui.theme.WxColors
import com.im.nativeapp.ui.theme.colorFromHex
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val session: SessionStore,
    private val settings: SettingsStore,
    private val apiClient: ApiClient,
    private val conversations: ConversationRepository,
    private val messages: MessageRepository
) : ViewModel() {

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast

    private val _cacheSize = MutableStateFlow(-1L)
    val cacheSize: StateFlow<Long> = _cacheSize

    /** 导出聊天记录要选会话：直接复用会话仓库的列表 */
    val conversationList: StateFlow<List<ConversationVO>> = conversations.list

    /** 版本信息 + 更新检查结果 */
    data class VersionState(
        val localName: String = "0.0.0",
        val localCode: Int = 0,
        val checking: Boolean = false,
        val latestName: String = "",
        val latestCode: Int = 0,
        val notes: String = "",
        val downloadUrl: String = "",
        val message: String = ""
    )

    private val _version = MutableStateFlow(readLocalVersion())
    val version: StateFlow<VersionState> = _version

    private fun readLocalVersion(): VersionState = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else info.versionCode
        VersionState(localName = info.versionName ?: "0.0.0", localCode = code)
    }.getOrDefault(VersionState())

    /**
     * 检查更新：读取服务器上的静态清单 {当前服务器}/app-version.json，
     * 用 versionCode 比大小（versionName 仅供展示），字段见 版本记录.md。
     */
    fun checkUpdate() {
        val cur = _version.value
        if (cur.checking) return
        _version.value = cur.copy(checking = true, message = "")
        viewModelScope.launch {
            val result = runCatching {
                val url = session.currentServer().trimEnd('/') + "/app-version.json"
                val req = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
                apiClient.mediaHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    JSONObject(resp.body?.string().orEmpty())
                }
            }
            val latest = result.getOrNull()
            _version.value = if (latest == null) {
                val err = result.exceptionOrNull()
                cur.copy(checking = false, message = "检查失败：" + (err?.message ?: "网络异常"))
            } else {
                val code = latest.optInt("versionCode", 0)
                cur.copy(
                    checking = false,
                    latestCode = code,
                    latestName = latest.optString("versionName"),
                    notes = latest.optString("notes"),
                    downloadUrl = latest.optString("url"),
                    message = if (code > cur.localCode) "发现新版本" else "已是最新版本"
                )
            }
        }
    }

    fun server(): String = session.currentServer()
    val username: String get() = auth.currentUser.value.username
    fun themeModeNow(): String = settings.themeMode
    fun liveQualityNow(): String = settings.liveQuality
    fun notifySoundNow(): Boolean = settings.notifySound

    fun init() {
        viewModelScope.launch { refreshCacheSize() }
    }

    fun setThemeMode(v: String) = viewModelScope.launch { settings.setThemeMode(v) }
    fun setThemeColor(v: String) = viewModelScope.launch { settings.setThemeColor(v) }
    fun setFontScale(v: Float) = viewModelScope.launch { settings.setFontScale(v) }
    fun setAvatarSize(v: String) = viewModelScope.launch { settings.setAvatarSize(v) }
    fun setBubbleShape(v: String) = viewModelScope.launch { settings.setBubbleShape(v) }
    fun setBubbleMeColor(v: String) = viewModelScope.launch { settings.setBubbleMeColor(v) }
    fun setBubblePeerColor(v: String) = viewModelScope.launch { settings.setBubblePeerColor(v) }
    fun setChatBg(mode: String, color: String) = viewModelScope.launch { settings.setChatBg(mode, color) }
    fun setShowPreview(v: Boolean) = viewModelScope.launch { settings.setShowPreview(v) }
    fun setReadReceipt(v: Boolean) = viewModelScope.launch { settings.setReadReceipt(v) }
    fun setImageZoom(v: Boolean) = viewModelScope.launch { settings.setImageZoom(v) }
    fun setWatermark(v: Boolean) = viewModelScope.launch { settings.setWatermark(v) }
    fun setAllowSaveImage(v: Boolean) = viewModelScope.launch { settings.setAllowSaveImage(v) }
    fun setDebugLog(v: Boolean) = viewModelScope.launch { settings.setDebugLog(v) }
    fun setNotifyEnabled(v: Boolean) = viewModelScope.launch { settings.setNotifyEnabled(v) }
    fun setNotifySound(v: Boolean) = viewModelScope.launch { settings.setNotifySound(v) }
    fun setLiveQuality(v: String) = viewModelScope.launch { settings.setLiveQuality(v) }
    fun setFontFamily(v: String) = viewModelScope.launch { settings.setFontFamily(v) }
    fun setListDensity(v: String) = viewModelScope.launch { settings.setListDensity(v) }
    fun setChatBgOpacity(v: Float) = viewModelScope.launch { settings.setChatBgOpacity(v) }
    fun setEnterToSend(v: Boolean) = viewModelScope.launch { settings.setEnterToSend(v) }
    fun setAutoLoadHistory(v: Boolean) = viewModelScope.launch { settings.setAutoLoadHistory(v) }
    fun setAutoPlayVideo(v: Boolean) = viewModelScope.launch { settings.setAutoPlayVideo(v) }
    fun setPollUnread(v: Boolean) = viewModelScope.launch { settings.setPollUnread(v) }

    /** 相册选中的背景图复制进 filesDir（Uri 授权只在当次可用，必须落本地） */
    fun setChatBgImage(uri: Uri) {
        viewModelScope.launch {
            try {
                val dest = withContext(Dispatchers.IO) {
                    val file = File(context.filesDir, "chat_bg_${System.currentTimeMillis()}.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    } ?: error("无法读取所选图片")
                    // 旧图删掉，避免内部存储里堆积
                    UiPrefs.chatBgImage.takeIf { it.isNotBlank() }?.let(::File)
                        ?.takeIf { it.exists() && it.absolutePath != file.absolutePath }
                        ?.delete()
                    file
                }
                settings.setChatBgImage(dest.absolutePath)
                _toast.tryEmit("聊天背景已更新")
            } catch (e: Exception) {
                _toast.tryEmit("背景图片保存失败：${e.message ?: ""}")
            }
        }
    }

    fun clearChatBgImage() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                UiPrefs.chatBgImage.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }?.delete()
            }
            settings.setChatBgImage("")
        }
    }

    fun setMediaCacheEnabled(v: Boolean) {
        viewModelScope.launch {
            settings.setMediaCacheEnabled(v)
            rebuildImageLoader()
            refreshCacheSize()
        }
    }

    fun setMediaCacheLimitMb(v: Int) {
        viewModelScope.launch {
            settings.setMediaCacheLimitMb(v)
            rebuildImageLoader()
        }
    }

    /** Coil 2 的缓存配置只能在构造时给，改设置后整体重建并替换全局单例 */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private fun rebuildImageLoader() {
        runCatching { Coil.setImageLoader(ImApp.buildImageLoader(context, apiClient)) }
    }

    fun clearLocalMessages() {
        viewModelScope.launch {
            runCatching { messages.clearLocalCache() }
            refreshCacheSize()
            _toast.tryEmit("本机消息缓存已清除（云端记录不受影响）")
        }
    }

    fun refreshConversations() {
        viewModelScope.launch { runCatching { conversations.fetchList() } }
    }

    /** 导出云端完整会话记录（GET /api/message/export）为 JSON，落到 Downloads */
    fun exportChatHistory(conversationId: String, conversationName: String) {
        if (conversationId.isBlank()) {
            _toast.tryEmit("会话 ID 为空，无法导出")
            return
        }
        viewModelScope.launch {
            try {
                val envelope = apiClient.service(MessageApi::class.java).export(conversationId)
                if (envelope.code != 200) {
                    throw ApiError(envelope.code, envelope.message ?: envelope.msg ?: "导出失败")
                }
                val data: JsonElement = envelope.data ?: throw ApiError(-1, "导出数据为空")
                val text = apiClient.json.encodeToString(JsonElement.serializer(), data)
                val safeName = conversationName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "conversation" }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val fileName = "chat-$safeName-$stamp.json"
                val saved = withContext(Dispatchers.IO) { writeToDownloads(fileName, text) }
                _toast.tryEmit("已导出：$saved")
            } catch (e: Exception) {
                _toast.tryEmit(if (e is ApiError) e.message ?: "导出失败" else "导出失败：${e.message ?: ""}")
            }
        }
    }

    /**
     * API 29+ 写公共 Downloads（MediaStore 无需权限）；更低版本只能落本应用专属 Downloads 目录
     * （写公共目录要 WRITE_EXTERNAL_STORAGE，本 App 未申请，不伪造权限）。
     */
    private fun writeToDownloads(fileName: String, content: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建下载文件")
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                ?: error("无法写入下载文件")
            return "Downloads/$fileName"
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Downloads")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(content)
        return file.absolutePath
    }
    fun setServer(raw: String, onDone: () -> Unit) = viewModelScope.launch {
        session.setServer(raw)
        _toast.tryEmit("服务器地址已保存，重新登录后生效")
        onDone()
    }

    fun logout(onDone: () -> Unit) = viewModelScope.launch {
        auth.logout()
        onDone()
    }

    fun changePassword(oldPassword: String, newPassword: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                apiClient.service(UserApi::class.java)
                    .changePassword(
                        ChangePasswordRequest(oldPassword.ifBlank { null }, newPassword, true)
                    )
                    .unwrapUnit()
                auth.logout()
                _toast.tryEmit("密码已修改，请用新密码重新登录")
                onDone()
            } catch (e: Exception) {
                _toast.tryEmit(errorText(e, "修改密码失败"))
            }
        }
    }

    fun cancelAccount(password: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                apiClient.service(UserApi::class.java)
                    .cancelAccount(CancelAccountRequest(password))
                    .unwrapUnit()
                auth.logout()
                _toast.tryEmit("账号已注销")
                onDone()
            } catch (e: Exception) {
                _toast.tryEmit(errorText(e, "注销失败"))
            }
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheSize.value = runCatching { dirSize(context.cacheDir) }.getOrDefault(0L)
        }
    }

    fun clearMediaCache() {
        viewModelScope.launch {
            runCatching {
                Coil.imageLoader(context).apply {
                    memoryCache?.clear()
                    diskCache?.clear()
                }
            }
            refreshCacheSize()
            _toast.tryEmit("媒体缓存已清除")
        }
    }

    private fun dirSize(dir: File): Long =
        if (!dir.exists()) 0L
        else dir.listFiles()?.sumOf { if (it.isDirectory) dirSize(it) else it.length() } ?: 0L

    private fun errorText(e: Exception, fallback: String): String =
        if (e is ApiError) e.message ?: fallback else "$fallback：${e.message ?: ""}"
}

private fun fmtSize(bytes: Long): String = when {
    bytes <= 0 -> "0 MB"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}

/** 微信风设置行：标题 + 右侧控件/箭头 */
@Composable
private fun Row2(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        trailing()
    }
}

@Composable
private fun NavRow(title: String, onClick: () -> Unit) {
    // 整行可点（不只右侧箭头），对齐微信设置项的手感
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
    }
}

/**
 * 设置页：首页分组列表 + 各分组的二级页（微信式）。
 * page 取值：home / account / appearance / behavior / notify / privacy / cache / advanced
 */
@Composable
fun SettingsScreen(
    page: String,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onLoggedOut: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.init()
        vm.toast.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    val titles = mapOf(
        "home" to "设置",
        "account" to "账号与安全",
        "appearance" to "外观设置",
        "behavior" to "消息行为",
        "notify" to "消息提醒",
        "privacy" to "隐私与安全",
        "cache" to "本地缓存",
        "advanced" to "高级设置"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                titles[page] ?: "设置",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider()
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            when (page) {
                "account" -> AccountPage(vm, onLoggedOut)
                "appearance" -> AppearancePage(vm)
                "behavior" -> BehaviorPage(vm)
                "notify" -> NotifyPage(vm)
                "privacy" -> PrivacyPage(vm)
                "cache" -> CachePage(vm)
                "advanced" -> AdvancedPage(vm)
                else -> HomePage(onNavigate)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HomePage(onNavigate: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                NavRow("账号与安全") { onNavigate("account") }
                HorizontalDivider()
                NavRow("外观设置") { onNavigate("appearance") }
                HorizontalDivider()
                NavRow("消息行为") { onNavigate("behavior") }
                HorizontalDivider()
                NavRow("消息提醒") { onNavigate("notify") }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                NavRow("隐私与安全") { onNavigate("privacy") }
                HorizontalDivider()
                NavRow("本地缓存") { onNavigate("cache") }
                HorizontalDivider()
                NavRow("高级设置") { onNavigate("advanced") }
            }
        }
    }
}

/* ============================== 账号与安全 ============================== */

@Composable
private fun AccountPage(vm: SettingsViewModel, onLoggedOut: () -> Unit) {
    var pwdDialog by remember { mutableStateOf(false) }
    var logoutDialog by remember { mutableStateOf(false) }
    var cancelDialog by remember { mutableStateOf(false) }

    Column {
        Row2("当前账号", vm.username)
        HorizontalDivider()
        NavRow("修改密码") { pwdDialog = true }
        HorizontalDivider()
        NavRow("退出账号") { logoutDialog = true }
        HorizontalDivider()
        NavRow("注销账号（不可恢复）") { cancelDialog = true }
    }

    if (pwdDialog) {
        var oldPwd by remember { mutableStateOf("") }
        var newPwd by remember { mutableStateOf("") }
        var confirmPwd by remember { mutableStateOf("") }
        val ready = newPwd.length >= 6 && newPwd == confirmPwd
        AlertDialog(
            onDismissRequest = { pwdDialog = false },
            title = { Text("修改密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(oldPwd, { oldPwd = it }, label = { Text("原密码（从未设过可留空）") }, singleLine = true)
                    OutlinedTextField(
                        newPwd, { newPwd = it }, label = { Text("新密码（6 位以上）") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    OutlinedTextField(
                        confirmPwd, { confirmPwd = it }, label = { Text("确认新密码") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    if (newPwd.isNotEmpty() && newPwd.length < 6) {
                        Text("新密码至少 6 位", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    } else if (confirmPwd.isNotEmpty() && newPwd != confirmPwd) {
                        Text("两次输入的新密码不一致", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text("修改成功后所有设备都要用新密码重新登录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { pwdDialog = false; vm.changePassword(oldPwd, newPwd, onLoggedOut) },
                    enabled = ready
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { pwdDialog = false }) { Text("取消") } }
        )
    }

    if (logoutDialog) {
        AlertDialog(
            onDismissRequest = { logoutDialog = false },
            title = { Text("退出账号") },
            text = { Text("退出后需要重新登录，聊天记录仍保留在服务器。") },
            confirmButton = { TextButton(onClick = { logoutDialog = false; vm.logout(onLoggedOut) }) { Text("退出") } },
            dismissButton = { TextButton(onClick = { logoutDialog = false }) { Text("取消") } }
        )
    }

    if (cancelDialog) {
        var pwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { cancelDialog = false },
            title = { Text("注销账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("注销后账号将被永久删除：好友关系、群组、会话与消息都会清除，且无法恢复。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        pwd, { pwd = it }, label = { Text("登录密码") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { cancelDialog = false; vm.cancelAccount(pwd, onLoggedOut) }, enabled = pwd.isNotBlank()) {
                    Text("确认注销", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { cancelDialog = false }) { Text("取消") } }
        )
    }
}

/* ============================== 外观设置 ============================== */

@Composable
private fun AppearancePage(vm: SettingsViewModel) {
    var themeMode by remember { mutableStateOf(vm.themeModeNow()) }
    var fontScale by remember { mutableStateOf(UiPrefs.fontScale) }
    var bgOpacity by remember { mutableStateOf(UiPrefs.chatBgOpacity) }
    // 自定义取色弹窗目标：me / peer
    var pickerTarget by remember { mutableStateOf<String?>(null) }
    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setChatBgImage(uri)
    }

    Column {
        // 外观实时预览：背景（纯色/图片+不透明度）+ 气泡颜色/形状/字体/字号
        BubblePreviewCard()
        HorizontalDivider()
        Row2("主题模式") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("light" to "浅色", "dark" to "深色", "system" to "跟随系统").forEach { (v, label) ->
                    FilterChip(selected = themeMode == v, onClick = {
                        themeMode = v
                        vm.setThemeMode(v)
                    }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                }
            }
        }
        HorizontalDivider()
        Row2("主题色") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentPresets.forEach { (name, color) ->
                    val selected = UiPrefs.themeColor == name
                    Box(
                        modifier = Modifier
                            .size(if (selected) 24.dp else 20.dp)
                            .background(color, CircleShape)
                            .clickable { vm.setThemeColor(name) }
                    )
                }
            }
        }
        HorizontalDivider()
        Row2("全局字号", "${(fontScale * 100).toInt()}%") {
            Slider(
                value = fontScale,
                onValueChange = { fontScale = it },
                onValueChangeFinished = { vm.setFontScale(fontScale) },
                valueRange = 0.85f..1.4f,
                modifier = Modifier.width(150.dp)
            )
        }
        HorizontalDivider()
        Row2("字体种类", "作用在聊天气泡正文") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("system" to "系统", "serif" to "衬线", "mono" to "等宽").forEach { (v, label) ->
                    FilterChip(selected = UiPrefs.fontFamily == v, onClick = { vm.setFontFamily(v) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                }
            }
        }
        HorizontalDivider()
        Row2("会话列表密度", "控制会话行高与头像间距") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("compact" to "紧凑", "normal" to "标准", "loose" to "宽松").forEach { (v, label) ->
                    FilterChip(selected = UiPrefs.listDensity == v, onClick = { vm.setListDensity(v) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                }
            }
        }
        HorizontalDivider()
        Row2("头像大小") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("sm" to "小", "md" to "中", "lg" to "大").forEach { (v, label) ->
                    FilterChip(selected = UiPrefs.avatarSize == v, onClick = { vm.setAvatarSize(v) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                }
            }
        }
        HorizontalDivider()
        Row2("气泡样式") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("round" to "圆角", "plain" to "简约").forEach { (v, label) ->
                    FilterChip(selected = UiPrefs.bubbleShape == v, onClick = { vm.setBubbleShape(v) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                }
            }
        }
        HorizontalDivider()
        Row2("我方气泡颜色") {
            ColorDots(UiPrefs.bubbleMeColor, { vm.setBubbleMeColor(it) }, { pickerTarget = "me" })
        }
        HorizontalDivider()
        Row2("对方气泡颜色") {
            ColorDots(UiPrefs.bubblePeerColor, { vm.setBubblePeerColor(it) }, { pickerTarget = "peer" })
        }
        HorizontalDivider()
        Row2("聊天背景") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "default" to "#EDEDED",
                    "color1" to "#F5F5F5",
                    "color2" to "#DDEDE0",
                    "color3" to "#E8E4F0"
                ).forEach { (label, hex) ->
                    val active = UiPrefs.chatBgMode == label
                    FilterChip(
                        selected = active,
                        onClick = { vm.setChatBg(label, hex) },
                        label = { Text(if (label == "default") "默认" else hex, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
        HorizontalDivider()
        Row2(
            "聊天背景图片",
            if (UiPrefs.chatBgImage.isNotBlank()) "已设置：图片作为聊天页背景" else "未设置：使用上面的纯色背景"
        ) {
            Row {
                TextButton(onClick = {
                    bgPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("选择图片") }
                if (UiPrefs.chatBgImage.isNotBlank()) {
                    TextButton(onClick = { vm.clearChatBgImage() }) {
                        Text("清除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (UiPrefs.chatBgImage.isNotBlank()) {
            HorizontalDivider()
            Row2("背景图不透明度", "${(bgOpacity * 100).toInt()}%") {
                Slider(
                    value = bgOpacity,
                    onValueChange = { bgOpacity = it },
                    onValueChangeFinished = { vm.setChatBgOpacity(bgOpacity) },
                    valueRange = 0.3f..1.0f,
                    modifier = Modifier.width(150.dp)
                )
            }
        }
    }

    pickerTarget?.let { target ->
        val initial = if (target == "me") UiPrefs.bubbleMeColor else UiPrefs.bubblePeerColor
        HsvColorDialog(
            initialHex = initial.ifBlank { if (target == "me") "#95EC69" else "#FFFFFF" },
            onDismiss = { pickerTarget = null },
            onSave = { hex ->
                if (target == "me") vm.setBubbleMeColor(hex) else vm.setBubblePeerColor(hex)
                pickerTarget = null
            }
        )
    }
}

/** 外观实时预览卡：读 UiPrefs.rev，任一外观项改动立即重绘 */
@Composable
private fun BubblePreviewCard() {
    val rev by UiPrefs.rev.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    val meColor = remember(rev) {
        colorFromHex(UiPrefs.bubbleMeColor) ?: if (dark) WxColors.BubbleSelfDark else WxColors.BubbleSelf
    }
    val peerColor = remember(rev) {
        colorFromHex(UiPrefs.bubblePeerColor) ?: if (dark) WxColors.BubblePeerDark else WxColors.BubblePeer
    }
    val onColor = if (dark) WxColors.OnBubbleDark else WxColors.OnBubble
    val shape = remember(rev) {
        if (UiPrefs.bubbleShape == "plain") RoundedCornerShape(2.dp) else RoundedCornerShape(8.dp)
    }
    val bgImage = remember(rev) {
        UiPrefs.chatBgImage.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
    }
    val solidBg = remember(rev) {
        (if (UiPrefs.chatBgMode != "default") colorFromHex(UiPrefs.chatBgColor) else null)
            ?: if (dark) WxColors.ChatBgDark else WxColors.ChatBg
    }
    val opacity = remember(rev) { UiPrefs.chatBgOpacity.coerceIn(0.3f, 1.0f) }

    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            if (bgImage != null) {
                AsyncImage(
                    model = bgImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background((if (dark) Color.Black else Color.White).copy(alpha = 1f - opacity))
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(solidBg))
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "气泡实时预览",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                PreviewBubble(
                    self = true,
                    text = "这是一条我方示例消息",
                    bubbleColor = meColor,
                    contentColor = onColor,
                    shape = shape
                )
                PreviewBubble(
                    self = false,
                    text = "这是一条对方示例消息",
                    bubbleColor = peerColor,
                    contentColor = onColor,
                    shape = shape
                )
            }
        }
    }
}

@Composable
private fun PreviewBubble(
    self: Boolean,
    text: String,
    bubbleColor: Color,
    contentColor: Color,
    shape: androidx.compose.ui.graphics.Shape
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (self) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .background(bubbleColor, shape)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = previewFontFamily()),
                color = contentColor
            )
        }
    }
}

private fun previewFontFamily(): FontFamily = when (UiPrefs.fontFamily) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.Default
}

/** 色相/饱和度/明度取色弹窗（android.graphics.Color.HSVToColor → #RRGGBB） */
@Composable
private fun HsvColorDialog(
    initialHex: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val hsv = remember(initialHex) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(hexToColor(initialHex).toArgb(), it) }
    }
    var hue by remember { mutableStateOf(hsv[0]) }
    var saturation by remember { mutableStateOf(hsv[1]) }
    var value by remember { mutableStateOf(hsv[2]) }
    val preview = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义颜色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(preview, RoundedCornerShape(8.dp))
                )
                Text("色相 ${hue.toInt()}°", style = MaterialTheme.typography.labelSmall)
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                Text("饱和度 ${(saturation * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..1f)
                Text("明度 ${(value * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(value = value, onValueChange = { value = it }, valueRange = 0f..1f)
                Text(
                    String.format("#%06X", 0xFFFFFF and preview.toArgb()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(String.format("#%06X", 0xFFFFFF and preview.toArgb()))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ColorDots(current: String, onPick: (String) -> Unit, onCustom: () -> Unit = {}) {
    val presets = listOf("#95EC69", "#FFFFFF", "#C9E7FF", "#FFE3B5", "#FFC9C9", "#D9D9D9")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { hex ->
            val color = hexToColor(hex)
            val selected = current == hex
            Box(
                modifier = Modifier
                    .size(if (selected) 24.dp else 20.dp)
                    .background(color, CircleShape)
                    .clickable { onPick(if (selected) "" else hex) }
            )
        }
        // 「自定义」入口：用 + 号圆点塞在色块行末尾，点开 HSV 取色弹窗
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable { onCustom() },
            contentAlignment = Alignment.Center
        ) {
            Text("+", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

private fun hexToColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.White)

/* ============================== 消息行为 ============================== */

@Composable
private fun BehaviorPage(vm: SettingsViewModel) {
    var preview by remember { mutableStateOf(UiPrefs.showPreview) }
    var receipt by remember { mutableStateOf(UiPrefs.readReceipt) }
    var zoom by remember { mutableStateOf(UiPrefs.imageZoom) }

    Column {
        Row2("会话列表消息预览", "关闭后只显示「收到一条新消息」") {
            Switch(checked = preview, onCheckedChange = { preview = it; vm.setShowPreview(it) })
        }
        HorizontalDivider()
        Row2("已读回执", "关闭后对方看不到你的已读状态") {
            Switch(checked = receipt, onCheckedChange = { receipt = it; vm.setReadReceipt(it) })
        }
        HorizontalDivider()
        Row2("图片点击放大预览") {
            Switch(checked = zoom, onCheckedChange = { zoom = it; vm.setImageZoom(it) })
        }
        HorizontalDivider()
        Row2("回车发送", "开启后 Enter 发送、Shift+Enter 换行（接外接键盘/输入法发送键）") {
            Switch(checked = UiPrefs.enterToSend, onCheckedChange = { vm.setEnterToSend(it) })
        }
        HorizontalDivider()
        Row2("自动加载历史消息", "关闭后滚动到顶部不再自动拉取，只保留「加载更多历史消息」按钮") {
            Switch(checked = UiPrefs.autoLoadHistory, onCheckedChange = { vm.setAutoLoadHistory(it) })
        }
        HorizontalDivider()
        Row2("视频自动播放", "进入视频播放页自动开始播放；关闭则等点播放") {
            Switch(checked = UiPrefs.autoPlayVideo, onCheckedChange = { vm.setAutoPlayVideo(it) })
        }
        HorizontalDivider()
        Row2("断线期间轮询未读", "WebSocket 断开/重连中每 15s 刷新一次会话列表") {
            Switch(checked = UiPrefs.pollUnread, onCheckedChange = { vm.setPollUnread(it) })
        }
        HorizontalDivider()
        Row2("直播默认清晰度", "档位不存在时自动回落") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("auto" to "自动", "480" to "480p", "720" to "720p", "1080" to "1080p").forEach { (v, label) ->
                    FilterChip(selected = vm.liveQualityNow() == v, onClick = { vm.setLiveQuality(v) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                }
            }
        }
    }
}

/* ============================== 消息提醒 ============================== */

@Composable
private fun NotifyPage(vm: SettingsViewModel) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(UiPrefs.notifyEnabled) }
    var sound by remember { mutableStateOf(vm.notifySoundNow()) }
    var permissionOn by remember { mutableStateOf(notificationsEnabled(context)) }

    Column {
        Row2("消息提醒", "仅本设备生效；被 @ 会打破会话免打扰") {
            Switch(checked = enabled, onCheckedChange = { enabled = it; vm.setNotifyEnabled(it) })
        }
        HorizontalDivider()
        Row2("新消息提示音") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    RingtoneManager.getRingtone(context, uri)?.play()
                }) { Text("试听") }
                Switch(checked = sound, onCheckedChange = { sound = it; vm.setNotifySound(it) })
            }
        }
        HorizontalDivider()
        Row2("系统通知权限", if (permissionOn) "已允许" else "未允许，收不到系统通知") {
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                permissionOn = notificationsEnabled(context)
            }) { Text("去设置") }
        }
        HorizontalDivider()
        Row2("说明", "App 退到后台时由前台服务保活推送；系统「省电模式」限制后台时可能延迟") {}
    }
}

private fun notificationsEnabled(context: Context): Boolean =
    (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()

/* ============================== 隐私与安全 ============================== */

@Composable
private fun PrivacyPage(vm: SettingsViewModel) {
    var watermark by remember { mutableStateOf(UiPrefs.watermark) }
    var allowSave by remember { mutableStateOf(UiPrefs.allowSaveImage) }

    Column {
        Row2("聊天界面水印", "叠加账号信息，降低截图泄露风险") {
            Switch(checked = watermark, onCheckedChange = { watermark = it; vm.setWatermark(it) })
        }
        HorizontalDivider()
        Row2("禁止保存聊天图片", "关闭图片长按「保存到相册」") {
            Switch(checked = !allowSave, onCheckedChange = { allowSave = !it; vm.setAllowSaveImage(!it) })
        }
        HorizontalDivider()
        Row2("多端消息共享", "本机发的消息同时上屏到其它登录端", ) {
            Text("始终开启", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/* ============================== 本地缓存 ============================== */

@Composable
private fun CachePage(vm: SettingsViewModel) {
    val size by vm.cacheSize.collectAsStateWithLifecycle()
    var limitMb by remember { mutableStateOf(UiPrefs.mediaCacheLimitMb) }
    var confirmClearLocal by remember { mutableStateOf(false) }

    Column {
        Row2("存储占用", if (size < 0) "统计中…" else fmtSize(size)) {
            TextButton(onClick = { vm.refreshCacheSize() }) { Text("刷新") }
        }
        HorizontalDivider()
        Row2("媒体缓存开关", "关闭后图片/视频不落盘也不占内存缓存，每次重新下载") {
            Switch(checked = UiPrefs.mediaCacheEnabled, onCheckedChange = { vm.setMediaCacheEnabled(it) })
        }
        HorizontalDivider()
        Row2(
            "媒体缓存上限",
            if (limitMb == 0) "不限（默认按可用空间 2%）" else "$limitMb MB"
        ) {
            Slider(
                value = limitMb.toFloat(),
                onValueChange = { limitMb = it.toInt() },
                onValueChangeFinished = { vm.setMediaCacheLimitMb(limitMb) },
                valueRange = 0f..2048f,
                modifier = Modifier.width(150.dp)
            )
        }
        HorizontalDivider()
        Row2("媒体缓存", "聊天图片/视频的本地缓存，不是云端备份") {
            TextButton(onClick = { vm.clearMediaCache() }) { Text("清除", color = MaterialTheme.colorScheme.error) }
        }
        HorizontalDivider()
        Row2("本机会话消息缓存", "消息在本机 Room 有落库；清除只删本地副本，云端记录不受影响") {
            TextButton(onClick = { confirmClearLocal = true }) {
                Text("清除", color = MaterialTheme.colorScheme.error)
            }
        }
        HorizontalDivider()
        Row2("下载路径自定义", "Android 无对应能力：下载统一进系统 Downloads（或 App 专属目录），不提供桌面式路径设置") {}
        HorizontalDivider()
        Row2("提示", "清除媒体缓存只会删除本机缓存文件，聊天记录仍在服务器") {}
    }

    if (confirmClearLocal) {
        AlertDialog(
            onDismissRequest = { confirmClearLocal = false },
            title = { Text("清除本机消息缓存？") },
            text = { Text("将删除本机已缓存的全部会话消息（含图片文字气泡数据），重新打开会话时会从服务器拉取最新一页。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearLocal = false
                    vm.clearLocalMessages()
                }) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClearLocal = false }) { Text("取消") } }
        )
    }
}

/* ============================== 高级设置 ============================== */

@Composable
private fun AdvancedPage(vm: SettingsViewModel) {
    var debug by remember { mutableStateOf(UiPrefs.debugLog) }
    var serverDialog by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var updateDialog by remember { mutableStateOf(false) }
    val ver by vm.version.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    // 后端清单里的 versionCode 比本地大 → 弹一次更新说明
    LaunchedEffect(ver.message, ver.latestCode) {
        if (ver.message == "发现新版本") updateDialog = true
    }

    if (updateDialog) {
        AlertDialog(
            onDismissRequest = { updateDialog = false },
            title = { Text("发现新版本 ${ver.latestName}") },
            text = {
                Text(
                    ver.notes.ifBlank {
                        "当前 ${ver.localName} (${ver.localCode}) → ${ver.latestName} (${ver.latestCode})"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    updateDialog = false
                    if (ver.downloadUrl.isNotBlank()) {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ver.downloadUrl))) }
                    }
                }) { Text(if (ver.downloadUrl.isNotBlank()) "去下载" else "知道了") }
            },
            dismissButton = if (ver.downloadUrl.isNotBlank()) {
                { TextButton(onClick = { updateDialog = false }) { Text("关闭") } }
            } else null
        )
    }

    Column {
        Row2("后端服务器", vm.server()) {
            TextButton(onClick = { serverDialog = true }) { Text("修改") }
        }
        HorizontalDivider()
        Row2(
            "版本号",
            buildString {
                append("当前 ${ver.localName}")
                if (ver.message.isNotBlank()) append(" · ${ver.message}")
                if (ver.message == "发现新版本" && ver.latestName.isNotBlank()) append(" ${ver.latestName}")
            }
        ) {
            TextButton(onClick = { vm.checkUpdate() }, enabled = !ver.checking) {
                if (ver.checking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("检查更新")
                }
            }
        }
        HorizontalDivider()
        Row2("调试日志", "输出网络与信令日志到 Logcat") {
            Switch(checked = debug, onCheckedChange = { debug = it; vm.setDebugLog(it) })
        }
        HorizontalDivider()
        Row2("导出聊天记录（JSON）", "从服务端导出完整会话记录，写入系统 Downloads") {
            TextButton(onClick = {
                vm.refreshConversations()
                exportDialog = true
            }) { Text("导出") }
        }
        HorizontalDivider()
        Row2("录屏审计", "Android 无对应能力：桌面端专属的屏幕录制/审计能力，移动端不提供") {}
        HorizontalDivider()
        Row2("网络代理", "Android 无对应能力：统一走系统级代理（系统设置 → 网络/Wi‑Fi 代理）") {}
        HorizontalDivider()
        Row2("全局快捷键", "Android 无对应能力：桌面端专属，移动端仅支持输入法/外接键盘的局部快捷键") {}
    }

    if (exportDialog) {
        val list by vm.conversationList.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { exportDialog = false },
            title = { Text("选择要导出的会话") },
            text = {
                if (list.isEmpty()) {
                    Text("暂无会话列表，请先回到「消息」页刷新后再试", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(list, key = { it.conversationId ?: it.name }) { c ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        exportDialog = false
                                        vm.exportChatHistory(c.conversationId ?: "", c.name)
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(c.name, style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { exportDialog = false }) { Text("取消") } }
        )
    }

    if (serverDialog) {
        var raw by remember { mutableStateOf(vm.server()) }
        AlertDialog(
            onDismissRequest = { serverDialog = false },
            title = { Text("服务器地址") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(raw, { raw = it }, label = { Text("如 https://im.dsaol.asia") }, singleLine = true)
                    Text(
                        "留空恢复默认地址（生产 ${SessionStore.PROD_SERVER} / 测试 ${SessionStore.TEST_SERVER}）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            },
            confirmButton = { TextButton(onClick = { serverDialog = false; vm.setServer(raw.trim()) {} }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { serverDialog = false }) { Text("取消") } }
        )
    }
}

/* ============================== 小工具 ============================== */
