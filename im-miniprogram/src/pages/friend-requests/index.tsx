import { useState } from 'react'
import { Button, Text, View } from '@tarojs/components'
import Taro, { useDidShow, usePullDownRefresh } from '@tarojs/taro'
import classNames from 'classnames'
import Avatar from '@/components/Avatar'
import EmptyState from '@/components/EmptyState'
import { friendApi } from '@/services/api'
import { useAppStore } from '@/store/useAppStore'
import type { FriendRequest } from '@/types/im'
import { formatFullTime } from '@/utils/format'
import { openChat, showError } from '@/utils/ui'
import styles from './index.module.scss'

export default function FriendRequestsPage() {
  const [tab, setTab] = useState<'received' | 'sent'>('received')
  const [received, setReceived] = useState<FriendRequest[]>([])
  const [sent, setSent] = useState<FriendRequest[]>([])
  const [loading, setLoading] = useState(true)
  const loadFriends = useAppStore((state) => state.loadFriends)
  const loadConversations = useAppStore((state) => state.loadConversations)
  const loadPending = useAppStore((state) => state.loadFriendPending)

  const refresh = async (): Promise<void> => {
    try {
      const [incoming, outgoing] = await Promise.all([friendApi.received(), friendApi.sent()])
      setReceived(incoming)
      setSent(outgoing)
      await loadPending()
    } catch (error) {
      console.error('[Friend] requests failed', error)
      showError(error, '好友申请加载失败')
    } finally {
      setLoading(false)
    }
  }
  useDidShow(() => { void refresh() })
  usePullDownRefresh(() => { void refresh().finally(() => Taro.stopPullDownRefresh()) })

  const handle = async (request: FriendRequest, accept: boolean): Promise<void> => {
    const id = request.id || request.requestId || ''
    try {
      if (accept) {
        const conversationId = await friendApi.accept(id)
        Taro.showToast({ title: '已添加好友', icon: 'success' })
        await Promise.all([loadFriends(), loadConversations()])
        if (conversationId) openChat(conversationId, request.peerNickname || request.peerUsername || '聊天')
      } else {
        await friendApi.reject(id)
        Taro.showToast({ title: '已拒绝申请', icon: 'none' })
      }
      await refresh()
    } catch (error) {
      console.error('[Friend] handle request failed', error)
      showError(error)
    }
  }

  const items = tab === 'received' ? received : sent
  return (
    <View className={styles.page}>
      <View className={styles.tabs}><View className={classNames(styles.tab, tab === 'received' && styles.active)} onClick={() => setTab('received')}>收到的申请 {received.filter((item) => item.status === 0).length || ''}</View><View className={classNames(styles.tab, tab === 'sent' && styles.active)} onClick={() => setTab('sent')}>我发出的</View></View>
      <Button className={classNames(styles.button, styles.primary, styles.add)} onClick={() => Taro.navigateTo({ url: '/pages/add-friend/index' })}>添加新朋友</Button>
      {!loading && !items.length ? <EmptyState title={tab === 'received' ? '暂无收到的申请' : '暂无发出的申请'} description='通过账号添加好友后，申请会显示在这里' /> : <View className={styles.list}>{items.map((request) => {
        const id = request.id || request.requestId || ''
        const pending = request.status === 0 && tab === 'received' && request.actionable !== false
        return <View className={styles.card} key={id}><View className={styles.main}><Avatar name={request.peerNickname || request.peerUsername} url={request.peerAvatar} /><View className={styles.copy}><Text className={styles.name}>{request.peerNickname || request.peerUsername || '用户'}</Text><Text className={styles.account}>@{request.peerUsername || request.peerUserId || '-'}</Text></View>{!pending && <Text className={styles.status}>{request.statusDesc || ['待处理', '已同意', '已拒绝', '已过期'][request.status || 0]}</Text>}</View>{request.verifyMessage && <View className={styles.message}>验证消息：{request.verifyMessage}</View>}<Text className={styles.time}>{formatFullTime(request.createTime)}</Text>{pending && <View className={styles.actions}><Button className={styles.button} onClick={() => void handle(request, false)}>拒绝</Button><Button className={classNames(styles.button, styles.primary)} onClick={() => void handle(request, true)}>同意</Button></View>}</View>
      })}</View>}
    </View>
  )
}
