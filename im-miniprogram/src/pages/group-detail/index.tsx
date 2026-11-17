import { useState } from 'react'
import { Button, Input, Text, Textarea, View } from '@tarojs/components'
import Taro, { useDidShow, usePullDownRefresh, useRouter } from '@tarojs/taro'
import Avatar from '@/components/Avatar'
import Loading from '@/components/Loading'
import { groupApi } from '@/services/api'
import { useAppStore } from '@/store/useAppStore'
import type { GroupInfo, GroupMember } from '@/types/im'
import { confirm, openChat, showError } from '@/utils/ui'
import styles from './index.module.scss'

export default function GroupDetailPage() {
  const router = useRouter()
  const id = String(router.params.id || '')
  const user = useAppStore((state) => state.user)
  const friends = useAppStore((state) => state.friends)
  const loadFriends = useAppStore((state) => state.loadFriends)
  const loadGroups = useAppStore((state) => state.loadGroups)
  const loadConversations = useAppStore((state) => state.loadConversations)
  const [group, setGroup] = useState<GroupInfo | null>(null)
  const [members, setMembers] = useState<GroupMember[]>([])
  const [name, setName] = useState('')
  const [notice, setNotice] = useState('')
  const [nickname, setNickname] = useState('')
  const [showInvite, setShowInvite] = useState(false)

  const refresh = async (): Promise<void> => {
    if (!id) return
    try {
      const [detail, page] = await Promise.all([groupApi.detail(id), groupApi.members(id)])
      setGroup(detail)
      setMembers(page.records || [])
      setName(detail.name)
      setNotice(detail.notice || detail.announcement || '')
      setNickname(detail.myNickname || detail.myGroupNickname || '')
    } catch (error) {
      console.error('[Group] detail failed', error)
      showError(error, '群资料加载失败')
    }
  }
  useDidShow(() => { void refresh() })
  usePullDownRefresh(() => { void refresh().finally(() => Taro.stopPullDownRefresh()) })
  const canManage = group?.myRole === 1 || group?.myRole === 2

  const save = async (field: 'name' | 'notice' | 'nickname'): Promise<void> => {
    if (!group) return
    try {
      if (field === 'nickname') await groupApi.updateMyNickname(id, nickname.trim())
      else await groupApi.update(id, field === 'name' ? { name: name.trim() } : { notice: notice.trim() })
      Taro.showToast({ title: '保存成功', icon: 'success' })
      await Promise.all([refresh(), loadGroups()])
    } catch (error) { showError(error, '保存失败') }
  }

  const toggleInvite = async (): Promise<void> => {
    if (!showInvite) await loadFriends().catch((error: unknown) => showError(error))
    setShowInvite(!showInvite)
  }

  const invite = async (userId: string): Promise<void> => {
    try {
      await groupApi.addMembers(id, [userId])
      Taro.showToast({ title: '已邀请入群', icon: 'success' })
      await refresh()
    } catch (error) { showError(error, '邀请失败') }
  }

  const removeMember = async (member: GroupMember): Promise<void> => {
    if (!canManage || member.userId === user?.id || member.role === 1) return
    if (group?.myRole === 2 && member.role === 2) return Taro.showToast({ title: '管理员不能移除管理员', icon: 'none' })
    if (!await confirm(`确定将 ${member.displayName || member.nickname || '该成员'} 移出群聊吗？`)) return
    try { await groupApi.removeMember(id, member.userId); await refresh() } catch (error) { showError(error, '移除成员失败') }
  }

  const quit = async (): Promise<void> => {
    if (group?.myRole === 1) return Taro.showToast({ title: '群主暂不能直接退群，请联系后端转让群主', icon: 'none' })
    if (!await confirm('退出后将不再接收该群消息。', '退出群聊')) return
    try {
      await groupApi.quit(id)
      await Promise.all([loadGroups(), loadConversations()])
      Taro.navigateBack()
    } catch (error) { showError(error, '退出群聊失败') }
  }

  if (!group) return <Loading text='正在加载群资料' />
  const candidates = friends.filter((friend) => !members.some((member) => member.userId === friend.friendId))
  return <View className={styles.page}>
    <View className={styles.hero}><Avatar className={styles.avatar} name={group.name} url={group.avatar} size={128} /><Text className={styles.name}>{group.name}</Text><Text className={styles.meta}>{group.memberCount || members.length} 位成员 · {group.myRole === 1 ? '我是群主' : group.myRole === 2 ? '我是管理员' : '我是成员'}</Text>{group.conversationId && <Button className={styles.chat} onClick={() => openChat(group.conversationId || '', group.name)}>进入群聊</Button>}</View>
    <View className={styles.card}><View className={styles.cardTitle}><Text>群公告</Text>{canManage && <Button className={styles.save} onClick={() => void save('notice')}>保存</Button>}</View>{canManage ? <Textarea className={styles.textarea} maxlength={500} value={notice} onInput={(event) => setNotice(event.detail.value)} placeholder='填写群公告' placeholderClass='placeholder' /> : <Text className={styles.notice}>{group.notice || group.announcement || '暂无群公告'}</Text>}</View>
    {canManage && <View className={styles.card}><View className={styles.cardTitle}><Text>群名称</Text><Button className={styles.save} onClick={() => void save('name')}>保存</Button></View><Input className={styles.input} maxlength={30} value={name} onInput={(event) => setName(event.detail.value)} /></View>}
    <View className={styles.card}><View className={styles.cardTitle}><Text>我的群昵称</Text><Button className={styles.save} onClick={() => void save('nickname')}>保存</Button></View><Input className={styles.input} maxlength={30} value={nickname} onInput={(event) => setNickname(event.detail.value)} placeholder='留空则使用账号昵称' placeholderClass='placeholder' /></View>
    <View className={styles.card}><View className={styles.cardTitle}><Text>群成员（{members.length}）</Text><Text className={styles.smallAction} onClick={() => void toggleInvite()}>{showInvite ? '收起' : '邀请成员'}</Text></View><View className={styles.members}>{members.map((member) => <View className={styles.member} key={member.userId} onLongPress={() => void removeMember(member)}><Avatar name={member.displayName || member.nickname} url={member.avatar} size={72} online={member.online} /><Text className={styles.memberName}>{member.displayName || member.nickname || member.username}</Text>{member.role !== 3 && <Text className={styles.role}>{member.role === 1 ? '群主' : '管理员'}</Text>}</View>)}<View className={styles.member} onClick={() => void toggleInvite()}><View className={styles.invite}>+</View><Text className={styles.memberName}>邀请</Text></View></View>{showInvite && <View className={styles.inviteList}>{candidates.length ? candidates.map((friend) => <View className={styles.inviteItem} key={friend.friendId}><Avatar name={friend.displayName || friend.nickname} url={friend.avatar} size={64} /><Text className={styles.inviteName}>{friend.displayName || friend.nickname}</Text><Button className={styles.inviteButton} onClick={() => void invite(friend.friendId)}>邀请</Button></View>) : <Text className={styles.hint}>所有好友都已在群内</Text>}</View>}</View>
    {group.inviteCode && <View className={styles.card}><View className={styles.codeRow}><Text className={styles.code}>邀请码：{group.inviteCode}</Text><Text className={styles.copy} onClick={() => void Taro.setClipboardData({ data: group.inviteCode || '' })}>复制</Text></View></View>}
    <Button className={styles.quit} onClick={() => void quit()}>退出群聊</Button><Text className={styles.hint}>管理员可长按成员进行移除</Text>
  </View>
}
