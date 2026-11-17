import { useState } from 'react'
import { Button, Input, Text, Textarea, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import classNames from 'classnames'
import { friendApi, groupApi } from '@/services/api'
import { showError } from '@/utils/ui'
import styles from './index.module.scss'

export default function AddFriendPage() {
  const router = useRouter()
  const [tab, setTab] = useState<'friend' | 'group'>(router.params.mode === 'group' ? 'group' : 'friend')
  const [account, setAccount] = useState('')
  const [inviteCode, setInviteCode] = useState('')
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(false)

  const submit = async (): Promise<void> => {
    if (tab === 'friend' && !account.trim()) return Taro.showToast({ title: '请输入好友账号或用户 ID', icon: 'none' })
    if (tab === 'group' && !inviteCode.trim()) return Taro.showToast({ title: '请输入群邀请码', icon: 'none' })
    setLoading(true)
    try {
      if (tab === 'friend') {
        const value = account.trim()
        await friendApi.apply({ targetAccount: value, verifyMessage: message.trim() || undefined })
        Taro.showToast({ title: '好友申请已发送', icon: 'success' })
        setTimeout(() => Taro.navigateBack(), 600)
      } else {
        const group = await groupApi.join(inviteCode.trim(), message.trim() || undefined)
        Taro.showToast({ title: '已加入群聊', icon: 'success' })
        setTimeout(() => Taro.redirectTo({ url: `/pages/group-detail/index?id=${group.groupId}` }), 600)
      }
    } catch (error) {
      console.error('[Social] apply failed', error)
      showError(error, tab === 'friend' ? '申请发送失败' : '加入群聊失败')
    } finally {
      setLoading(false)
    }
  }

  return <View className={styles.page}><Text className={styles.title}>建立新的连接</Text><Text className={styles.desc}>添加好友开始单聊，或凭邀请码加入群聊。</Text><View className={styles.tabs}><View className={classNames(styles.tab, tab === 'friend' && styles.active)} onClick={() => setTab('friend')}>添加好友</View><View className={classNames(styles.tab, tab === 'group' && styles.active)} onClick={() => setTab('group')}>加入群聊</View></View><View className={styles.card}>
    {tab === 'friend' ? <><Text className={styles.label}>好友账号 / 用户 ID</Text><Input className={styles.input} value={account} onInput={(event) => setAccount(event.detail.value)} placeholder='请输入对方用户名或用户 ID' placeholderClass='placeholder' /></> : <><Text className={styles.label}>群邀请码</Text><Input className={styles.input} value={inviteCode} onInput={(event) => setInviteCode(event.detail.value)} placeholder='请输入群主分享的邀请码' placeholderClass='placeholder' /></>}
    <Text className={styles.label}>验证消息（选填）</Text><Textarea className={styles.textarea} maxlength={100} value={message} onInput={(event) => setMessage(event.detail.value)} placeholder={tab === 'friend' ? '介绍一下自己，让对方知道你是谁' : '向群主说明入群原因'} placeholderClass='placeholder' />
    <Button className={styles.submit} loading={loading} disabled={loading} onClick={() => void submit()}>{tab === 'friend' ? '发送好友申请' : '申请加入群聊'}</Button>
  </View><View className={styles.tip}>请尊重对方意愿，不要频繁发送重复申请。好友申请需对方同意，需要审核的群聊需管理员通过。</View></View>
}
