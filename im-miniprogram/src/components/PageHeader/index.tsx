import type { ReactNode } from 'react'
import { Text, View } from '@tarojs/components'
import styles from './index.module.scss'

interface PageHeaderProps {
  title: string
  subtitle?: string
  right?: ReactNode
}

export default function PageHeader({ title, subtitle, right }: PageHeaderProps) {
  return (
    <View className={styles.header}>
      <View className={styles.copy}>
        <Text className={styles.title}>{title}</Text>
        {subtitle && <Text className={styles.subtitle}>{subtitle}</Text>}
      </View>
      {right && <View className={styles.right}>{right}</View>}
    </View>
  )
}
