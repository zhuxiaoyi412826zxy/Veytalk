import { useState } from 'react'
import { Button, Input, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import classNames from 'classnames'
import { authApi } from '@/services/api'
import { useAppStore } from '@/store/useAppStore'
import { showError } from '@/utils/ui'
import styles from './index.module.scss'

export default function RegisterPage() {
  const acceptLogin = useAppStore((state) => state.acceptLogin)
  const [username, setUsername] = useState('')
  const [nickname, setNickname] = useState('')
  const [phone, setPhone] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [agreed, setAgreed] = useState(false)
  const [loading, setLoading] = useState(false)

  const submit = async (): Promise<void> => {
    if (!/^[a-zA-Z][a-zA-Z0-9_]{3,19}$/.test(username)) return Taro.showToast({ title: '账号需以字母开头，长度 4-20 位', icon: 'none' })
    if (password.length < 8) return Taro.showToast({ title: '密码至少需要 8 位', icon: 'none' })
    if (password !== confirmPassword) return Taro.showToast({ title: '两次输入的密码不一致', icon: 'none' })
    if (phone && !/^1\d{10}$/.test(phone)) return Taro.showToast({ title: '手机号格式不正确', icon: 'none' })
    if (email && !/^\S+@\S+\.\S+$/.test(email)) return Taro.showToast({ title: '邮箱格式不正确', icon: 'none' })
    if (!agreed) return Taro.showToast({ title: '请先阅读并同意服务规范', icon: 'none' })
    setLoading(true)
    try {
      const result = await authApi.register({
        username,
        password,
        nickname: nickname.trim() || undefined,
        phone: phone || undefined,
        email: email.trim() || undefined
      })
      acceptLogin(result)
      Taro.showToast({ title: '注册成功', icon: 'success' })
      Taro.reLaunch({ url: '/pages/conversations/index' })
    } catch (error) {
      console.error('[Auth] register failed', error)
      showError(error, '注册失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <View className={styles.page}>
      <View className={styles.heading}><Text className={styles.title}>创建你的账号</Text><Text className={styles.subtitle}>一个账号，连接好友、群组与 AI</Text></View>
      <View className={styles.card}>
        <Text className={styles.label}><Text className={styles.required}>* </Text>登录账号</Text>
        <Input className={styles.input} value={username} onInput={(event) => setUsername(event.detail.value.trim())} placeholder='4-20 位字母、数字或下划线' placeholderClass='placeholder' />
        <Text className={styles.label}>昵称</Text>
        <Input className={styles.input} maxlength={20} value={nickname} onInput={(event) => setNickname(event.detail.value)} placeholder='让大家如何称呼你' placeholderClass='placeholder' />
        <Text className={styles.label}>手机号（选填）</Text>
        <Input className={styles.input} type='number' maxlength={11} value={phone} onInput={(event) => setPhone(event.detail.value)} placeholder='用于短信登录' placeholderClass='placeholder' />
        <Text className={styles.label}>邮箱（选填）</Text>
        <Input className={styles.input} value={email} onInput={(event) => setEmail(event.detail.value)} placeholder='name@example.com' placeholderClass='placeholder' />
        <Text className={styles.label}><Text className={styles.required}>* </Text>设置密码</Text>
        <Input className={styles.input} password value={password} onInput={(event) => setPassword(event.detail.value)} placeholder='至少 8 位字符' placeholderClass='placeholder' />
        <Text className={styles.label}><Text className={styles.required}>* </Text>确认密码</Text>
        <Input className={styles.input} password value={confirmPassword} onInput={(event) => setConfirmPassword(event.detail.value)} placeholder='再次输入密码' placeholderClass='placeholder' />
        <View className={styles.agreement} onClick={() => setAgreed(!agreed)}>
          <View className={classNames(styles.check, agreed && styles.checked)}>✓</View>
          <Text>我已阅读并同意平台服务规范与隐私政策，并知悉账号注销后无法恢复。</Text>
        </View>
        <Button className={styles.submit} loading={loading} disabled={loading} onClick={() => void submit()}>注册并登录</Button>
        <Text className={styles.login} onClick={() => Taro.navigateBack()}>已有账号，返回登录</Text>
      </View>
    </View>
  )
}
