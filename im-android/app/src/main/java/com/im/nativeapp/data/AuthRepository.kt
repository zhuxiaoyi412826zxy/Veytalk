package com.im.nativeapp.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.core.network.CaptchaVO
import com.im.nativeapp.core.network.LoginRequest
import com.im.nativeapp.core.network.LoginVO
import com.im.nativeapp.core.network.RegisterRequest
import com.im.nativeapp.core.network.SendCodeVO
import com.im.nativeapp.core.network.SendSmsCodeRequest
import com.im.nativeapp.core.network.SmsLoginRequest
import com.im.nativeapp.core.network.UserInfoVO
import com.im.nativeapp.core.network.unwrap
import com.im.nativeapp.core.network.unwrapUnit
import com.im.nativeapp.core.push.ImPushService
import com.im.nativeapp.core.ws.ImWebSocket
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 认证仓库。所有登录入口成功后都走 applyLogin：
 * 存 token（含后端下发的 tokenName）→ 建立 WS 长连。
 */
@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: ApiClient,
    private val session: SessionStore,
    private val ws: ImWebSocket
) {
    suspend fun login(account: String, password: String): LoginVO {
        val vo: LoginVO = apiClient.api()
            .login(LoginRequest(account = account, password = password, deviceId = SessionStore.DEVICE_ID))
            .unwrap(apiClient.json)
        return applyLogin(vo)
    }

    suspend fun loginBySms(phone: String, smsCode: String): LoginVO {
        val vo: LoginVO = apiClient.api()
            .loginBySms(SmsLoginRequest(phone = phone, smsCode = smsCode, deviceId = SessionStore.DEVICE_ID))
            .unwrap(apiClient.json)
        return applyLogin(vo)
    }

    /** 注册成功即返回登录态（对齐 Web 端：不必再调一次 login） */
    suspend fun register(
        username: String,
        password: String,
        nickname: String?,
        phone: String?,
        email: String?
    ): LoginVO {
        val vo: LoginVO = apiClient.api()
            .register(
                RegisterRequest(
                    username = username,
                    password = password,
                    nickname = nickname,
                    phone = phone,
                    email = email,
                    deviceId = SessionStore.DEVICE_ID
                )
            )
            .unwrap(apiClient.json)
        return applyLogin(vo)
    }

    suspend fun captcha(): CaptchaVO =
        apiClient.api().captchaImage().unwrap(apiClient.json)

    suspend fun sendSmsCode(phone: String, captchaKey: String, captchaCode: String): SendCodeVO =
        apiClient.api()
            .sendSmsCode(SendSmsCodeRequest(phone = phone, captchaKey = captchaKey, captchaCode = captchaCode))
            .unwrap(apiClient.json)

    /** 冷启动恢复登录态；1002/2010 时清凭证，由上层跳回登录页 */
    suspend fun fetchMe(): UserInfoVO {
        val me: UserInfoVO = apiClient.api().me().unwrap(apiClient.json)
        _currentUser.value = me
        return me
    }

    /** 当前登录用户资料：登录成功与冷启动 fetchMe 后填充，供「我的」页与气泡 self 判定用 */
    private val _currentUser = kotlinx.coroutines.flow.MutableStateFlow(UserInfoVO())
    val currentUser: kotlinx.coroutines.flow.StateFlow<UserInfoVO> = _currentUser

    suspend fun logout() {
        runCatching { apiClient.api().logout().unwrapUnit() }
        teardownLocal()
    }

    /** 被顶下线/登出的本地收尾：断长连 + 停保活服务 + 清凭证 + 清用户资料（不调后端） */
    suspend fun teardownLocal() {
        ws.disconnect()
        runCatching { context.stopService(Intent(context, ImPushService::class.java)) }
        session.clearToken()
        _currentUser.value = UserInfoVO()
    }

    private suspend fun applyLogin(vo: LoginVO): LoginVO {
        if (vo.token.isEmpty()) return vo
        session.setToken(vo.token, vo.tokenName)
        vo.userInfo?.let { _currentUser.value = it }
        ws.connect()
        // 前台服务保活收消息（局域网无 FCM）：所有登录入口都经过这里
        runCatching { ContextCompat.startForegroundService(context, Intent(context, ImPushService::class.java)) }
        return vo
    }
}
