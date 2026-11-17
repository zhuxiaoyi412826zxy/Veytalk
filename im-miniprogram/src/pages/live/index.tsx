import { useState } from 'react'
import { Text, View } from '@tarojs/components'
import Taro, { useDidShow, usePullDownRefresh } from '@tarojs/taro'
import classNames from 'classnames'
import Avatar from '@/components/Avatar'
import EmptyState from '@/components/EmptyState'
import RemoteImage from '@/components/RemoteImage'
import { liveApi } from '@/services/api'
import type { LiveRoom } from '@/types/im'
import { showError } from '@/utils/ui'
import styles from './index.module.scss'

export default function LivePage() {
  const [rooms, setRooms] = useState<LiveRoom[]>([])
  const [status, setStatus] = useState<number | undefined>(undefined)
  const refresh = async (nextStatus = status): Promise<void> => {
    try { const result = await liveApi.page(1, 40, nextStatus); setRooms(result.records || []) } catch (error) { console.error('[Live] list failed', error); showError(error, '直播加载失败') }
  }
  useDidShow(() => { void refresh() })
  usePullDownRefresh(() => { void refresh().finally(() => Taro.stopPullDownRefresh()) })
  const filter = (value?: number): void => { setStatus(value); void refresh(value) }
  return <View className={styles.page}><View className={styles.heading}><Text className={styles.title}>直播广场</Text><Text className={styles.subtitle}>进入直播间，与主播实时互动</Text></View><View className={styles.filters}><View className={classNames(styles.filter, status === undefined && styles.active)} onClick={() => filter()}>全部</View><View className={classNames(styles.filter, status === 1 && styles.active)} onClick={() => filter(1)}>直播中</View><View className={classNames(styles.filter, status === 0 && styles.active)} onClick={() => filter(0)}>已结束</View></View>{rooms.length ? <View className={styles.grid}>{rooms.map((room) => { const id = room.id || room.roomId || ''; return <View className={styles.card} key={id} onClick={() => Taro.navigateTo({ url: `/pages/live-room/index?id=${id}` })}><View className={styles.coverWrap}><RemoteImage className={styles.cover} src={room.cover} mode='aspectFill' /><Text className={classNames(styles.status, room.status === 1 && styles.liveStatus)}>{room.status === 1 ? '直播中' : '已结束'}</Text>{room.gate?.type && room.gate.type !== 'none' && <Text className={styles.gate}>{room.gate.type === 'paid' ? '付费' : '密码'}</Text>}</View><View className={styles.copy}><Text className={styles.roomTitle}>{room.title}</Text><View className={styles.meta}><Avatar name={room.anchorName} url={room.anchorAvatar} size={40} /><Text className={styles.anchor}>{room.anchorName || '主播'}</Text><Text>{room.onlineCount || room.viewerCount || 0} 人</Text></View></View></View>})}</View> : <EmptyState title='暂无直播房间' description='主播开播后，房间会出现在这里' />}<Text className={styles.note}>小程序端支持观看与互动，开播请使用桌面端</Text></View>
}
