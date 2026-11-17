import { Button, Text, View } from '@tarojs/components'
import styles from './index.module.scss'

interface EmptyStateProps {
  title: string
  description?: string
  actionText?: string
  onAction?: () => void
}

export default function EmptyState({ title, description, actionText, onAction }: EmptyStateProps) {
  return (
    <View className={styles.empty}>
      <View className={styles.mark}><Text>V</Text></View>
      <Text className={styles.title}>{title}</Text>
      {description && <Text className={styles.description}>{description}</Text>}
      {actionText && onAction && <Button className={styles.action} onClick={onAction}>{actionText}</Button>}
    </View>
  )
}
