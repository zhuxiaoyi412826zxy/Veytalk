import { useMemo, useState } from 'react'
import { Input, Text, View } from '@tarojs/components'
import Taro, { useDidShow, usePullDownRefresh } from '@tarojs/taro'
import Avatar from '@/components/Avatar'
import EmptyState from '@/components/EmptyState'
import PageHeader from '@/components/PageHeader'
import { conversationApi, friendApi } from '@/services/api'
import { useAppStore } from '@/store/useAppStore'
import type { Friend } from '@/types/im'
import { confirm, openChat, showError } from '@/utils/ui'
import styles from './index.module.scss'

export default function FriendsPage() {
  const friends = useAppStore((state) => state.friends)
  const pending = useAppStore((state) => state.friendPending)
  const loadFriends = useAppStore((state) => state.loadFriends)
  const loadPending = useAppStore((state) => state.loadFriendPending)
  const [keyword, setKeyword] = useState('')

  const refresh = async (): Promise<void> => {
    try {
      await Promise.all([loadFriends(), loadPending()])
    } catch (error) {
      console.error('[Friend] load failed', error)
      showError(error, '好友列表加载失败')
    }
  }
  useDidShow(() => { void refresh() })
  usePullDownRefresh(() => { void refresh().finally(() => Taro.stopPullDownRefresh()) })

  const filtered = useMemo(() => {
    const value = keyword.trim().toLowerCase()
    if (!value) return friends
    return friends.filter((friend) => `${friend.displayName || ''} ${friend.nickname} ${friend.username || ''} ${friend.remark || ''}`.toLowerCase().includes(value))
  }, [friends, keyword])

  const startChat = async (friend: Friend): Promise<void> => {
    try {
      const conversation = await conversationApi.single(friend.friendId)
      openChat(conversation.conversationId, friend.displayName || friend.remark || friend.nickname)
    } catch (error) {
      console.error('[Friend] open chat failed', error)
      showError(error, '无法发起聊天')
    }
  }

  const removeFriend = async (friend: Friend): Promise<void> => {
    const name = friend.displayName || friend.remark || friend.nickname
    if (!await confirm(`删除好友 ${name} 后，需要重新申请才能添加。`, '删除好友')) return
    try {
      await friendApi.remove(friend.friendId)
      await refresh()
      Taro.showToast({ title: '已删除好友', icon: 'success' })
    } catch (error) {
      showError(error)
    }
  }

  return (
    <View className={styles.page}>
      <PageHeader title='好友' subtitle={`${friends.length} 位好友`} right={<View className={styles.add} onClick={() => Taro.navigateTo({ url: '/pages/add-friend/index' })}>+</View>} />
      <View className={styles.searchWrap}><Input className={styles.search} value={keyword} onInput={(event) => setKeyword(event.detail.value)} placeholder='搜索昵称、备注或账号' placeholderClass='placeholder' /></View>
      <View className={styles.shortcuts}>
        <View className={styles.shortcut} onClick={() => Taro.navigateTo({ url: '/pages/friend-requests/index' })}><View className={styles.shortcutIcon}>新</View><Text className={styles.shortcutTitle}>新的朋友</Text><Text className={styles.shortcutDesc}>处理好友申请</Text>{pending > 0 && <View className={styles.badge}>{pending}</View>}</View>
        <View className={styles.shortcut} onClick={() => Taro.navigateTo({ url: '/pages/groups/index' })}><View className={styles.shortcutIcon}>群</View><Text className={styles.shortcutTitle}>我的群聊</Text><Text className={styles.shortcutDesc}>查看与创建群组</Text></View>
      </View>
      <Text className={styles.sectionTitle}>{keyword ? `找到 ${filtered.length} 位好友` : '全部好友'}</Text>
      {filtered.length ? <View className={styles.list}>{filtered.map((friend, index) => <View key={friend.friendId}><View className={styles.item} onClick={() => void startChat(friend)} onLongPress={() => void removeFriend(friend)}><Avatar name={friend.displayName || friend.nickname} url={friend.avatar} online={friend.online} /><View className={styles.copy}><Text className={styles.name}>{friend.remark || friend.displayName || friend.nickname}</Text><Text className={styles.signature}>{friend.signature || `@${friend.username || friend.friendId}`}</Text></View><Text className={styles.arrow}>›</Text></View>{index < filtered.length - 1 && <View className={styles.divider} />}</View>)}</View> : <EmptyState title={keyword ? '没有找到好友' : '还没有好友'} description={keyword ? '试试其他关键词' : '通过账号搜索，添加你的第一位好友'} actionText='添加好友' onAction={() => Taro.navigateTo({ url: '/pages/add-friend/index' })} />}
      {friends.length > 0 && <Text className={styles.note}>点击好友发起聊天，长按可删除好友</Text>}
    </View>
  )
}
