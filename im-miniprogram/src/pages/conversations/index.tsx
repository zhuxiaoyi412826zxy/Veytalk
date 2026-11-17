import { useEffect, useMemo, useState } from 'react'
import { Input, Text, View } from '@tarojs/components'
import Taro, { useDidShow, usePullDownRefresh } from '@tarojs/taro'
import classNames from 'classnames'
import Avatar from '@/components/Avatar'
import EmptyState from '@/components/EmptyState'
import PageHeader from '@/components/PageHeader'
import { conversationApi } from '@/services/api'
import { useAppStore } from '@/store/useAppStore'
import type { Conversation } from '@/types/im'
import { conversationTitle, formatTime, messageSummary } from '@/utils/format'
import { confirm, openChat, showError } from '@/utils/ui'
import styles from './index.module.scss'

export default function ConversationsPage() {
  const conversations = useAppStore((state) => state.conversations)
  const totalUnread = useAppStore((state) => state.totalUnread)
  const connection = useAppStore((state) => state.connection)
  const loadConversations = useAppStore((state) => state.loadConversations)
  const setActiveConversation = useAppStore((state) => state.setActiveConversation)
  const patchConversation = useAppStore((state) => state.patchConversation)
  const removeConversation = useAppStore((state) => state.removeConversation)
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(true)

  const refresh = async (): Promise<void> => {
    try {
      await loadConversations()
    } catch (error) {
      console.error('[Conversation] load failed', error)
      showError(error, '会话加载失败')
    } finally {
      setLoading(false)
    }
  }

  useDidShow(() => { void refresh() })
  usePullDownRefresh(() => { void refresh().finally(() => Taro.stopPullDownRefresh()) })

  useEffect(() => {
    if (totalUnread > 0) Taro.setTabBarBadge({ index: 0, text: totalUnread > 99 ? '99+' : String(totalUnread) }).catch(() => undefined)
    else Taro.removeTabBarBadge({ index: 0 }).catch(() => undefined)
  }, [totalUnread])

  const filtered = useMemo(() => {
    const value = keyword.trim().toLowerCase()
    if (!value) return conversations
    return conversations.filter((item) => `${conversationTitle(item)} ${item.lastMsgContent || ''}`.toLowerCase().includes(value))
  }, [conversations, keyword])

  const open = (item: Conversation): void => {
    setActiveConversation(item.conversationId)
    patchConversation(item.conversationId, { unreadCount: 0 })
    openChat(item.conversationId, conversationTitle(item))
  }

  const showActions = async (item: Conversation): Promise<void> => {
    const isTop = Boolean(item.top || item.isTop)
    const isMuted = Boolean(item.muted || item.isMuted)
    const actions = [isTop ? '取消置顶' : '置顶会话', isMuted ? '取消免打扰' : '消息免打扰', '删除会话']
    const result = await Taro.showActionSheet({ itemList: actions, alertText: conversationTitle(item) })
    try {
      if (result.tapIndex === 0) {
        await conversationApi.top(item.conversationId, !isTop)
        patchConversation(item.conversationId, { top: !isTop, isTop: !isTop, topTime: !isTop ? Date.now() : undefined })
      } else if (result.tapIndex === 1) {
        await conversationApi.mute(item.conversationId, !isMuted)
        patchConversation(item.conversationId, { muted: !isMuted, isMuted: !isMuted })
      } else if (result.tapIndex === 2 && await confirm('删除后将从会话列表移除，是否继续？', '删除会话')) {
        await conversationApi.remove(item.conversationId)
        removeConversation(item.conversationId)
      }
    } catch (error) {
      console.error('[Conversation] action failed', error)
      showError(error)
    }
  }

  const addAction = async (): Promise<void> => {
    const result = await Taro.showActionSheet({ itemList: ['添加好友', '创建群聊', '文件传输助手'] })
    if (result.tapIndex === 0) return Taro.navigateTo({ url: '/pages/add-friend/index' })
    if (result.tapIndex === 1) return Taro.navigateTo({ url: '/pages/group-create/index' })
    if (result.tapIndex === 2) {
      try {
        const conversationId = await conversationApi.assistant()
        const conversation = conversations.find((item) => item.conversationId === conversationId)
          || await conversationApi.detail(conversationId)
        open(conversation)
      } catch (error) {
        showError(error, '无法打开文件传输助手')
      }
    }
  }

  return (
    <View className={styles.page}>
      <PageHeader title='消息' subtitle={totalUnread ? `${totalUnread} 条未读消息` : '保持联系，分享此刻'} right={<View className={styles.add} onClick={() => void addAction()}>+</View>} />
      {connection !== 'connected' && <View className={styles.connection}>{connection === 'connecting' || connection === 'reconnecting' ? '正在连接实时消息服务…' : '实时服务未连接，下拉可刷新消息'}</View>}
      <View className={styles.searchWrap}><Input className={styles.search} value={keyword} onInput={(event) => setKeyword(event.detail.value)} placeholder='搜索会话或消息内容' placeholderClass='placeholder' /></View>
      <View className={styles.quick}>
        <View className={styles.quickItem} onClick={() => Taro.navigateTo({ url: '/pages/friend-requests/index' })}>新的朋友</View>
        <View className={styles.quickItem} onClick={() => Taro.navigateTo({ url: '/pages/groups/index' })}>我的群聊</View>
      </View>
      {!loading && filtered.length === 0 ? (
        <View className={styles.empty}><EmptyState title={keyword ? '没有匹配的会话' : '还没有消息'} description={keyword ? '换个关键词试试' : '添加一位好友，开始第一段对话'} actionText={keyword ? undefined : '添加好友'} onAction={keyword ? undefined : () => Taro.navigateTo({ url: '/pages/add-friend/index' })} /></View>
      ) : (
        <View className={styles.list}>
          {filtered.map((item, index) => {
            const unread = item.unreadCount || 0
            const summary = messageSummary(item.lastMessage, item.lastMsgType, item.lastMsgContent) || '暂无消息'
            return (
              <View key={item.conversationId}>
                <View className={classNames(styles.item, (item.top || item.isTop) && styles.topItem)} onClick={() => open(item)} onLongPress={() => void showActions(item)}>
                  {(item.top || item.isTop) && <View className={styles.pin} />}
                  <Avatar name={conversationTitle(item)} url={item.avatar} online={item.type === 1 ? item.online : undefined} />
                  <View className={styles.content}>
                    <View className={styles.row}><Text className={styles.name}>{conversationTitle(item)}</Text><Text className={styles.time}>{formatTime(item.lastMsgTime)}</Text></View>
                    <View className={styles.row}>
                      <Text className={styles.preview}>{item.atFlag && <Text className={styles.at}>[有人@我] </Text>}{summary}</Text>
                      <View className={styles.meta}>
                        {(item.muted || item.isMuted) && <Text className={styles.muted}>免打扰</Text>}
                        {unread > 0 && <View className={styles.badge}>{unread > 99 ? '99+' : unread}</View>}
                      </View>
                    </View>
                  </View>
                </View>
                {index < filtered.length - 1 && <View className={styles.divider} />}
              </View>
            )
          })}
        </View>
      )}
      {filtered.length > 0 && <Text className={styles.bottomNote}>长按会话可置顶、免打扰或删除</Text>}
    </View>
  )
}
