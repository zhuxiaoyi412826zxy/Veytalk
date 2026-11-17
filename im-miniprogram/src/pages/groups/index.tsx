import { Button, Text, View } from '@tarojs/components'
import Taro, { useDidShow, usePullDownRefresh } from '@tarojs/taro'
import classNames from 'classnames'
import Avatar from '@/components/Avatar'
import EmptyState from '@/components/EmptyState'
import { useAppStore } from '@/store/useAppStore'
import { showError } from '@/utils/ui'
import styles from './index.module.scss'

export default function GroupsPage() {
  const groups = useAppStore((state) => state.groups)
  const loadGroups = useAppStore((state) => state.loadGroups)
  const refresh = async (): Promise<void> => {
    try { await loadGroups() } catch (error) { console.error('[Group] list failed', error); showError(error, '群聊加载失败') }
  }
  useDidShow(() => { void refresh() })
  usePullDownRefresh(() => { void refresh().finally(() => Taro.stopPullDownRefresh()) })
  return <View className={styles.page}><View className={styles.actions}><Button className={styles.action} onClick={() => Taro.navigateTo({ url: '/pages/add-friend/index?mode=group' })}>邀请码加群</Button><Button className={classNames(styles.action, styles.primary)} onClick={() => Taro.navigateTo({ url: '/pages/group-create/index' })}>创建群聊</Button></View>{groups.length ? <View className={styles.list}>{groups.map((group, index) => <View key={group.groupId}><View className={styles.item} onClick={() => Taro.navigateTo({ url: `/pages/group-detail/index?id=${group.groupId}` })}><Avatar name={group.name} url={group.avatar} /><View className={styles.copy}><Text className={styles.name}>{group.name}</Text><Text className={styles.notice}>{group.notice || group.announcement || '暂无群公告'}</Text></View><View className={styles.meta}><Text className={styles.count}>{group.memberCount || 0} 人</Text><Text className={styles.arrow}>›</Text></View></View>{index < groups.length - 1 && <View className={styles.divider} />}</View>)}</View> : <EmptyState title='还没有加入群聊' description='创建一个新群，或输入邀请码加入已有群聊' actionText='创建群聊' onAction={() => Taro.navigateTo({ url: '/pages/group-create/index' })} />}</View>
}
