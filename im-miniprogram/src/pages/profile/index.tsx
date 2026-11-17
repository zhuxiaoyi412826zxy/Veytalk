import { Button, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import Avatar from '@/components/Avatar'
import { getServer } from '@/config/runtime'
import { authApi } from '@/services/api'
import { useAppStore } from '@/store/useAppStore'
import { confirm, showError } from '@/utils/ui'
import styles from './index.module.scss'

export default function ProfilePage() {
  const user = useAppStore((state) => state.user)
  const friends = useAppStore((state) => state.friends)
  const groups = useAppStore((state) => state.groups)
  const conversations = useAppStore((state) => state.conversations)
  const setUser = useAppStore((state) => state.setUser)
  const loadFriends = useAppStore((state) => state.loadFriends)
  const loadGroups = useAppStore((state) => state.loadGroups)
  const loadConversations = useAppStore((state) => state.loadConversations)
  const logout = useAppStore((state) => state.logout)

  useDidShow(() => {
    void authApi.me().then(setUser).catch((error: unknown) => console.error('[Profile] me failed', error))
    void Promise.all([loadFriends(), loadGroups(), loadConversations()]).catch((error: unknown) => console.error('[Profile] stats failed', error))
  })

  const signOut = async (): Promise<void> => {
    if (!await confirm('退出后需要重新登录才能接收消息。', '退出登录')) return
    try { await logout(); Taro.reLaunch({ url: '/pages/login/index' }) } catch (error) { showError(error, '退出失败') }
  }

  return <View className={styles.page}>
    <View className={styles.hero}><Text className={styles.brand}>VEYTALK</Text><View className={styles.user} onClick={() => Taro.navigateTo({ url: '/pages/profile-edit/index' })}><Avatar name={user?.nickname} url={user?.avatar} size={120} /><View className={styles.copy}><Text className={styles.name}>{user?.nickname || 'VeyTalk 用户'}</Text><Text className={styles.account}>@{user?.username || '-'} · ID {user?.id || '-'}</Text></View><Text className={styles.arrow}>›</Text></View></View>
    <View className={styles.stats}><View className={styles.stat}><Text className={styles.number}>{conversations.length}</Text><Text className={styles.label}>会话</Text></View><View className={styles.stat}><Text className={styles.number}>{friends.length}</Text><Text className={styles.label}>好友</Text></View><View className={styles.stat}><Text className={styles.number}>{groups.length}</Text><Text className={styles.label}>群聊</Text></View></View>
    <View className={styles.card}><View className={styles.item} onClick={() => Taro.navigateTo({ url: '/pages/profile-edit/index' })}><View className={styles.icon}>资</View><Text className={styles.itemTitle}>个人资料</Text><Text className={styles.value}>昵称与头像</Text><Text className={styles.arrow}>›</Text></View><View className={styles.divider} /><View className={styles.item} onClick={() => Taro.navigateTo({ url: '/pages/scan/index' })}><View className={styles.icon}>扫</View><Text className={styles.itemTitle}>扫一扫</Text><Text className={styles.value}>网页登录授权</Text><Text className={styles.arrow}>›</Text></View><View className={styles.divider} /><View className={styles.item} onClick={() => Taro.navigateTo({ url: '/pages/settings/index' })}><View className={styles.icon}>设</View><Text className={styles.itemTitle}>设置与账号安全</Text><Text className={styles.arrow}>›</Text></View></View>
    <View className={styles.card}><View className={styles.item}><View className={styles.icon}>服</View><Text className={styles.itemTitle}>当前服务器</Text><Text className={styles.value}>{getServer()}</Text></View></View>
    <View className={styles.limit}>小程序端已支持消息、好友、群组、视频、直播与 AI。原生实时音视频通话依赖 WebRTC 能力，当前版本未开放通话入口。</View>
    <Button className={styles.logout} onClick={() => void signOut()}>退出登录</Button><Text className={styles.version}>VeyTalk Mini · 1.0.0</Text>
  </View>
}
