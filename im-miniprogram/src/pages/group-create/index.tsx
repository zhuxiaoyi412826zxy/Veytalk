import { useEffect, useState } from 'react'
import { Button, Input, Text, Textarea, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import classNames from 'classnames'
import Avatar from '@/components/Avatar'
import EmptyState from '@/components/EmptyState'
import { groupApi } from '@/services/api'
import { useAppStore } from '@/store/useAppStore'
import { openChat, showError } from '@/utils/ui'
import styles from './index.module.scss'

export default function GroupCreatePage() {
  const friends = useAppStore((state) => state.friends)
  const loadFriends = useAppStore((state) => state.loadFriends)
  const loadGroups = useAppStore((state) => state.loadGroups)
  const loadConversations = useAppStore((state) => state.loadConversations)
  const [name, setName] = useState('')
  const [notice, setNotice] = useState('')
  const [selected, setSelected] = useState<string[]>([])
  const [loading, setLoading] = useState(false)
  useEffect(() => { void loadFriends().catch((error: unknown) => showError(error, '好友加载失败')) }, [loadFriends])
  const toggle = (id: string): void => setSelected((items) => items.includes(id) ? items.filter((item) => item !== id) : [...items, id])
  const submit = async (): Promise<void> => {
    if (!name.trim()) return Taro.showToast({ title: '请输入群名称', icon: 'none' })
    setLoading(true)
    try {
      const group = await groupApi.create({ name: name.trim(), notice: notice.trim() || undefined, memberIds: selected })
      await Promise.all([loadGroups(), loadConversations()])
      Taro.showToast({ title: '群聊已创建', icon: 'success' })
      if (group.conversationId) {
        Taro.navigateBack()
        setTimeout(() => openChat(group.conversationId || '', group.name), 300)
      } else Taro.redirectTo({ url: `/pages/group-detail/index?id=${group.groupId}` })
    } catch (error) {
      console.error('[Group] create failed', error)
      showError(error, '创建群聊失败')
    } finally { setLoading(false) }
  }
  return <View className={styles.page}><View className={styles.card}><Text className={styles.label}>群名称</Text><Input className={styles.input} maxlength={30} value={name} onInput={(event) => setName(event.detail.value)} placeholder='给群聊起个名字' placeholderClass='placeholder' /><Textarea className={styles.textarea} maxlength={200} value={notice} onInput={(event) => setNotice(event.detail.value)} placeholder='群公告（选填）' placeholderClass='placeholder' /></View><View className={styles.sectionTitle}><Text>选择好友</Text><Text className={styles.count}>已选 {selected.length} 人</Text></View>{friends.length ? <View className={styles.friends}>{friends.map((friend, index) => <View key={friend.friendId}><View className={styles.friend} onClick={() => toggle(friend.friendId)}><Avatar name={friend.displayName || friend.nickname} url={friend.avatar} size={72} /><Text className={styles.name}>{friend.remark || friend.displayName || friend.nickname}</Text><View className={classNames(styles.check, selected.includes(friend.friendId) && styles.checked)}>✓</View></View>{index < friends.length - 1 && <View className={styles.divider} />}</View>)}</View> : <EmptyState title='暂无可邀请的好友' description='可以先创建群聊，之后再邀请成员' />}<View className={styles.footer}><Button className={styles.submit} loading={loading} disabled={loading} onClick={() => void submit()}>创建群聊{selected.length ? `（${selected.length + 1} 人）` : ''}</Button></View></View>
}
