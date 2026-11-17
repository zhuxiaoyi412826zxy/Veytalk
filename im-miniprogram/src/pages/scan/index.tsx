import { useState } from 'react'
import { Button, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import classNames from 'classnames'
import Avatar from '@/components/Avatar'
import { authApi } from '@/services/api'
import { useAppStore } from '@/store/useAppStore'
import { showError } from '@/utils/ui'
import styles from './index.module.scss'

function parseLoginScene(raw: string): string {
  const value = raw.trim()
  if (!/^https?:\/\//i.test(value)) return ''
  const queryStart = value.indexOf('?')
  if (queryStart < 0 || !/\/(transfer|filehelper)$/i.test(value.slice(0, queryStart))) return ''
  const query = value.slice(queryStart + 1).split('#')[0]
  const pair = query.split('&').map((item) => item.split('=')).find(([key]) => key === 'scene')
  if (!pair?.[1]) return ''
  try { return decodeURIComponent(pair[1]) } catch { return '' }
}

export default function ScanPage() {
  const user = useAppStore((state) => state.user)
  const [scene, setScene] = useState('')
  const [authorized, setAuthorized] = useState(false)
  const [loading, setLoading] = useState(false)

  const resolve = async (raw: string): Promise<void> => {
    const value = parseLoginScene(raw)
    if (!value) return Taro.showToast({ title: '不是有效的 VeyTalk 网页登录二维码', icon: 'none', duration: 2500 })
    setScene(value)
    try { await authApi.qrScanned(value) } catch (error) { console.error('[Scan] mark failed', error); setScene(''); showError(error, '二维码已失效，请刷新网页') }
  }

  const scan = async (): Promise<void> => {
    try {
      const result = await Taro.scanCode({ onlyFromCamera: false, scanType: ['qrCode'] })
      await resolve(result.result)
    } catch (error) { if (!(error instanceof Error && error.message.includes('cancel'))) showError(error, '扫码失败') }
  }

  const authorize = async (): Promise<void> => {
    if (!scene || loading) return
    setLoading(true)
    try {
      await authApi.qrAuthorize(scene)
      setAuthorized(true)
    } catch (error) { console.error('[Scan] authorize failed', error); showError(error, '授权失败，请刷新网页二维码') } finally { setLoading(false) }
  }

  return <View className={styles.page}>
    {!scene ? <><View className={styles.hero}><View className={styles.scanMark}>＋</View><Text className={styles.title}>扫描网页登录码</Text><Text className={styles.desc}>扫描 VeyTalk 网页端展示的二维码，核对账号并确认后，即可登录网页。</Text></View><Button className={styles.scanButton} onClick={() => void scan()}>开始扫码</Button><Text className={styles.tip}>仅识别 /transfer 与 /filehelper 登录二维码{'\n'}不会在扫码后自动授权</Text></> : <View className={styles.card}>{authorized ? <><Text className={styles.success}>网页登录授权成功</Text><Text className={styles.desc}>网页将自动进入你的账号，现在可以返回继续使用小程序。</Text><Button className={classNames(styles.button, styles.primary)} onClick={() => Taro.navigateBack()}>完成</Button></> : <><Text className={styles.title}>确认登录网页端？</Text><View className={styles.account}><Avatar name={user?.nickname} url={user?.avatar} size={88} /><Text className={styles.name}>{user?.nickname || user?.username}</Text></View><View className={styles.warning}>确认后，该网页将以当前账号登录。若不是你本人发起的扫码，请取消，不要授权。</View><View className={styles.actions}><Button className={styles.button} onClick={() => setScene('')}>取消</Button><Button className={classNames(styles.button, styles.primary)} loading={loading} disabled={loading} onClick={() => void authorize()}>确认登录</Button></View></>}</View>}
  </View>
}
