import { useEffect, useRef, useState } from 'react'
import { Button, Image, Input, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import classNames from 'classnames'
import { DEFAULT_SERVER, getServer, isIpServer, normalizeServer, setServer } from '@/config/runtime'
import { authApi } from '@/services/api'
import { useAppStore } from '@/store/useAppStore'
import type { CaptchaInfo } from '@/types/im'
import { showError } from '@/utils/ui'
import styles from './index.module.scss'

export default function LoginPage() {
  const acceptLogin = useAppStore((state) => state.acceptLogin)
  const [mode, setMode] = useState<'account' | 'sms'>('account')
  const [account, setAccount] = useState('')
  const [password, setPassword] = useState('')
  const [phone, setPhone] = useState('')
  const [smsCode, setSmsCode] = useState('')
  const [captchaCode, setCaptchaCode] = useState('')
  const [captcha, setCaptcha] = useState<CaptchaInfo | null>(null)
  const [countdown, setCountdown] = useState(0)
  const [loading, setLoading] = useState(false)
  const [server, setServerValue] = useState(getServer())
  const [showServer, setShowServer] = useState(false)
  const timer = useRef<ReturnType<typeof setInterval> | null>(null)

  const loadCaptcha = async (): Promise<void> => {
    try {
      setCaptcha(await authApi.captcha())
      setCaptchaCode('')
    } catch (error) {
      console.error('[Auth] captcha failed', error)
      showError(error, '验证码加载失败')
    }
  }

  useEffect(() => () => { if (timer.current) clearInterval(timer.current) }, [])

  const startCountdown = (seconds: number): void => {
    setCountdown(seconds)
    if (timer.current) clearInterval(timer.current)
    timer.current = setInterval(() => {
      setCountdown((value) => {
        if (value <= 1 && timer.current) clearInterval(timer.current)
        return Math.max(0, value - 1)
      })
    }, 1000)
  }

  const switchMode = (next: 'account' | 'sms'): void => {
    setMode(next)
    if (next === 'sms' && !captcha) void loadCaptcha()
  }

  const sendCode = async (): Promise<void> => {
    if (!/^1\d{10}$/.test(phone)) return Taro.showToast({ title: '请输入正确的手机号', icon: 'none' })
    if (!captcha || !captchaCode.trim()) return Taro.showToast({ title: '请输入图形验证码', icon: 'none' })
    try {
      const result = await authApi.sendSms({ phone, scene: 'login', captchaKey: captcha.captchaKey, captchaCode: captchaCode.trim() })
      startCountdown(result.retryAfter || 60)
      if (result.debugCode) setSmsCode(result.debugCode)
      Taro.showToast({ title: '验证码已发送', icon: 'success' })
    } catch (error) {
      console.error('[Auth] sms failed', error)
      showError(error, '验证码发送失败')
      void loadCaptcha()
    }
  }

  const submit = async (): Promise<void> => {
    if (loading) return
    if (mode === 'account' && (!account.trim() || !password)) return Taro.showToast({ title: '请输入账号和密码', icon: 'none' })
    if (mode === 'sms' && (!phone || !smsCode.trim())) return Taro.showToast({ title: '请输入手机号和短信验证码', icon: 'none' })
    setLoading(true)
    try {
      const result = mode === 'account'
        ? await authApi.login(account.trim(), password)
        : await authApi.loginBySms(phone, smsCode.trim())
      acceptLogin(result)
      Taro.reLaunch({ url: '/pages/conversations/index' })
    } catch (error) {
      console.error('[Auth] login failed', error)
      showError(error, '登录失败')
    } finally {
      setLoading(false)
    }
  }

  const saveServer = (value: string): void => {
    const normalized = normalizeServer(value)
    if (!/^https:\/\//.test(normalized)) return Taro.showToast({ title: '线上服务器需使用 HTTPS 地址', icon: 'none' })
    if (isIpServer(normalized)) return Taro.showToast({ title: '小程序不支持 IP 直连，请填域名', icon: 'none' })
    setServer(normalized)
    setServerValue(normalized)
    setCaptcha(null)
    Taro.showToast({ title: '服务器已保存', icon: 'success' })
  }

  return (
    <View className={styles.page}>
      <View className={styles.hero}>
        <Text className={styles.brand}>VEYTALK</Text>
        <Text className={styles.title}>欢迎回来</Text>
        <Text className={styles.subtitle}>登录后，继续你的每一次对话</Text>
      </View>
      <View className={styles.card}>
        <View className={styles.tabs}>
          <View className={classNames(styles.tab, mode === 'account' && styles.activeTab)} onClick={() => switchMode('account')}>账号登录</View>
          <View className={classNames(styles.tab, mode === 'sms' && styles.activeTab)} onClick={() => switchMode('sms')}>短信登录</View>
        </View>
        <View className={styles.form}>
          {mode === 'account' ? (
            <>
              <Text className={styles.label}>账号</Text>
              <Input className={styles.input} value={account} onInput={(event) => setAccount(event.detail.value)} placeholder='用户名或手机号' placeholderClass='placeholder' />
              <Text className={styles.label}>密码</Text>
              <Input className={styles.input} password value={password} onInput={(event) => setPassword(event.detail.value)} placeholder='请输入密码' placeholderClass='placeholder' />
            </>
          ) : (
            <>
              <Text className={styles.label}>手机号</Text>
              <Input className={styles.input} type='number' maxlength={11} value={phone} onInput={(event) => setPhone(event.detail.value)} placeholder='请输入手机号' placeholderClass='placeholder' />
              <Text className={styles.label}>图形验证码</Text>
              <View className={styles.codeRow}>
                <Input className={classNames(styles.input, styles.codeInput)} value={captchaCode} onInput={(event) => setCaptchaCode(event.detail.value)} placeholder='输入图中字符' placeholderClass='placeholder' />
                {captcha?.image && <Image className={styles.captcha} src={captcha.image} mode='aspectFit' onClick={() => void loadCaptcha()} />}
              </View>
              <Text className={styles.label}>短信验证码</Text>
              <View className={styles.codeRow}>
                <Input className={classNames(styles.input, styles.codeInput)} type='number' maxlength={6} value={smsCode} onInput={(event) => setSmsCode(event.detail.value)} placeholder='6 位验证码' placeholderClass='placeholder' />
                <Button className={styles.codeButton} disabled={countdown > 0} onClick={() => void sendCode()}>{countdown > 0 ? `${countdown}s 后重发` : '获取验证码'}</Button>
              </View>
            </>
          )}
          <Button className={classNames(styles.submit, loading && styles.disabled)} loading={loading} disabled={loading} onClick={() => void submit()}>登录</Button>
          <View className={styles.links}>
            <Text onClick={() => Taro.navigateTo({ url: '/pages/register/index' })}>注册新账号</Text>
            <Text onClick={() => setShowServer(!showServer)}>服务器设置</Text>
          </View>
        </View>
      </View>
      {showServer && (
        <View className={styles.server}>
          <View className={styles.serverTitle}><Text>线上服务器</Text><Text className={styles.serverHint}>默认 443 端口</Text></View>
          <Input className={classNames(styles.input, styles.serverInput)} value={server} onInput={(event) => setServerValue(event.detail.value)} onBlur={() => saveServer(server)} />
          <View className={styles.serverActions}>
            <Button className={styles.serverOption} onClick={() => saveServer(DEFAULT_SERVER)}>使用默认域名</Button>
          </View>
          <Text className={styles.serverHint}>不支持用 IP 直连：证书只签了 im.dsaol.asia，IP 会因证书域名不匹配而握手失败</Text>
        </View>
      )}
      <Text className={styles.foot}>登录即代表你同意平台服务与隐私规范</Text>
    </View>
  )
}
