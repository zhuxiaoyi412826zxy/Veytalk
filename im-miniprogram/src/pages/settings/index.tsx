import { useState } from 'react'
import { Button, Input, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import classNames from 'classnames'
import { DEFAULT_SERVER, getServer, isIpServer, normalizeServer, setServer } from '@/config/runtime'
import { userApi } from '@/services/api'
import { reconnect } from '@/services/websocket'
import { useAppStore } from '@/store/useAppStore'
import { confirm, showError } from '@/utils/ui'
import styles from './index.module.scss'

export default function SettingsPage() {
  const logout = useAppStore((state) => state.logout)
  const [server, setServerValue] = useState(getServer())
  const [showPassword, setShowPassword] = useState(false)
  const [oldPassword, setOldPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [showCancel, setShowCancel] = useState(false)
  const [cancelPassword, setCancelPassword] = useState('')
  const [loading, setLoading] = useState(false)

  const saveServer = (value: string): void => {
    const normalized = normalizeServer(value)
    if (!/^https:\/\//.test(normalized)) return Taro.showToast({ title: '线上服务器必须使用 HTTPS', icon: 'none' })
    if (isIpServer(normalized)) return Taro.showToast({ title: '小程序不支持 IP 直连，请填域名', icon: 'none' })
    setServer(normalized)
    setServerValue(normalized)
    reconnect()
    Taro.showToast({ title: '服务器已切换', icon: 'success' })
  }

  const changePassword = async (): Promise<void> => {
    if (newPassword.length < 8) return Taro.showToast({ title: '新密码至少需要 8 位', icon: 'none' })
    if (newPassword !== confirmPassword) return Taro.showToast({ title: '两次输入的新密码不一致', icon: 'none' })
    if (!await confirm('修改后将退出所有设备，需要重新登录。', '修改密码')) return
    setLoading(true)
    try {
      await userApi.changePassword(oldPassword, newPassword, true)
      await logout(false)
      Taro.reLaunch({ url: '/pages/login/index' })
    } catch (error) { showError(error, '密码修改失败') } finally { setLoading(false) }
  }

  const cancelAccount = async (): Promise<void> => {
    if (!cancelPassword) return Taro.showToast({ title: '请输入登录密码确认身份', icon: 'none' })
    if (!await confirm('账号将匿名化并逻辑删除，所有资料无法恢复。此操作不可撤销！', '永久注销账号')) return
    setLoading(true)
    try {
      await userApi.cancelAccount(cancelPassword)
      await logout(false)
      Taro.reLaunch({ url: '/pages/login/index' })
    } catch (error) { showError(error, '账号注销失败') } finally { setLoading(false) }
  }

  return <View className={styles.page}>
    <Text className={styles.sectionTitle}>连接设置</Text><View className={styles.card}><View className={styles.row}><Text className={styles.label}>线上服务器</Text><Text className={styles.action} onClick={() => saveServer(server)}>保存</Text></View><Input className={styles.input} value={server} onInput={(event) => setServerValue(event.detail.value)} /><Button className={classNames(styles.button, styles.secondary)} onClick={() => saveServer(DEFAULT_SERVER)}>使用默认域名（推荐）</Button><Text className={styles.hint}>默认 HTTPS 443 端口。不支持 IP 直连：证书只签了 im.dsaol.asia，用 IP 会因证书域名不匹配而握手失败。切换后实时连接会重新建立，登录令牌保持不变。</Text></View>
    <Text className={styles.sectionTitle}>账号安全</Text><View className={styles.card}><View className={styles.row}><Text className={styles.label}>修改登录密码</Text><Text className={styles.action} onClick={() => setShowPassword(!showPassword)}>{showPassword ? '收起' : '修改'}</Text></View>{showPassword && <View className={styles.form}><Input className={styles.input} password value={oldPassword} onInput={(event) => setOldPassword(event.detail.value)} placeholder='当前密码（未设过密码可留空）' placeholderClass='placeholder' /><Input className={styles.input} password value={newPassword} onInput={(event) => setNewPassword(event.detail.value)} placeholder='新密码，至少 8 位' placeholderClass='placeholder' /><Input className={styles.input} password value={confirmPassword} onInput={(event) => setConfirmPassword(event.detail.value)} placeholder='再次输入新密码' placeholderClass='placeholder' /><Button className={styles.button} loading={loading} disabled={loading} onClick={() => void changePassword()}>确认修改并退出所有设备</Button></View>}</View>
    <Text className={styles.sectionTitle}>隐私与数据</Text><View className={styles.card}><View className={styles.row}><Text className={styles.label}>消息与文件</Text><Text className={styles.value}>数据保存在线上服务器，本地仅保存登录态与界面缓存</Text></View><View className={styles.divider} /><View className={styles.row}><Text className={styles.label}>音视频通话</Text><Text className={styles.value}>小程序端未开放 WebRTC 通话</Text></View></View>
    <Text className={styles.sectionTitle}>危险操作</Text><View className={styles.card}><View className={styles.row}><Text className={styles.label}>永久注销账号</Text><Text className={styles.action} onClick={() => setShowCancel(!showCancel)}>{showCancel ? '收起' : '注销'}</Text></View>{showCancel && <View className={styles.form}><Text className={styles.hint}>注销后账号匿名化并逻辑删除，不可恢复。请谨慎操作。</Text><Input className={styles.input} password value={cancelPassword} onInput={(event) => setCancelPassword(event.detail.value)} placeholder='输入登录密码确认' placeholderClass='placeholder' /><Button className={classNames(styles.button, styles.danger)} loading={loading} disabled={loading} onClick={() => void cancelAccount()}>永久注销我的账号</Button></View>}</View>
    <Text className={styles.sectionTitle} /><View className={styles.version}>VeyTalk Mini 1.0.0 · DeviceType mini</View>
  </View>
}
