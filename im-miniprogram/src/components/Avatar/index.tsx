import { useState } from 'react'
import { Text, View } from '@tarojs/components'
import classNames from 'classnames'
import RemoteImage from '@/components/RemoteImage'
import styles from './index.module.scss'

interface AvatarProps {
  name?: string
  url?: string
  size?: number
  online?: boolean
  className?: string
}

export default function Avatar({ name = '', url, size = 88, online, className }: AvatarProps) {
  const [failed, setFailed] = useState(false)
  const initial = (name || 'V').trim().slice(0, 1).toUpperCase()
  return (
    <View
      className={classNames(styles.avatar, className)}
      style={{ width: `${size}rpx`, height: `${size}rpx`, borderRadius: `${size / 2}rpx` }}
    >
      {url && !failed ? (
        <RemoteImage className={styles.image} src={url} mode='aspectFill' onError={() => setFailed(true)} />
      ) : (
        <Text className={styles.initial} style={{ fontSize: `${size * 0.38}rpx` }}>{initial}</Text>
      )}
      {online !== undefined && <View className={classNames(styles.online, { [styles.offline]: !online })} />}
    </View>
  )
}
