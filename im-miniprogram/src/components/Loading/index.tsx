import { Text, View } from '@tarojs/components'
import styles from './index.module.scss'

export default function Loading({ text = '加载中' }: { text?: string }) {
  return <View className={styles.loading}><View className={styles.spinner} /><Text>{text}</Text></View>
}
