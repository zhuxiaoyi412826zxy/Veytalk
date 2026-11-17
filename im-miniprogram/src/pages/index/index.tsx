import { useEffect } from 'react'
import { Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useAppStore } from '@/store/useAppStore'
import styles from './index.module.scss'

export default function IndexPage() {
  const bootstrap = useAppStore((state) => state.bootstrap)

  useEffect(() => {
    let active = true
    void bootstrap().then((user) => {
      if (!active) return
      Taro.reLaunch({ url: user ? '/pages/conversations/index' : '/pages/login/index' })
    })
    return () => { active = false }
  }, [bootstrap])

  return (
    <View className={styles.page}>
      <View className={styles.logo}><Text>V</Text></View>
      <Text className={styles.name}>VeyTalk</Text>
      <Text className={styles.slogan}>让每一次连接，都有回应</Text>
      <View className={styles.loading}><View className={styles.dot} /><Text>正在安全连接</Text></View>
    </View>
  )
}
