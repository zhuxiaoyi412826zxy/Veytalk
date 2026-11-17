import { useState } from 'react'
import { Button, Image, Input, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import Avatar from '@/components/Avatar'
import { authApi, userApi } from '@/services/api'
import { uploadFile } from '@/services/upload'
import { useAppStore } from '@/store/useAppStore'
import { showError } from '@/utils/ui'
import styles from './index.module.scss'

export default function ProfileEditPage() {
  const user = useAppStore((state) => state.user)
  const setUser = useAppStore((state) => state.setUser)
  const [nickname, setNickname] = useState(user?.nickname || '')
  const [avatarPath, setAvatarPath] = useState('')
  const [saving, setSaving] = useState(false)
  const choose = async (): Promise<void> => {
    try {
      const result = await Taro.chooseImage({ count: 1, sizeType: ['compressed'], sourceType: ['album', 'camera'] })
      setAvatarPath(result.tempFilePaths[0] || '')
    } catch (error) { if (!(error instanceof Error && error.message.includes('cancel'))) showError(error, '头像选择失败') }
  }
  const save = async (): Promise<void> => {
    if (!nickname.trim()) return Taro.showToast({ title: '昵称不能为空', icon: 'none' })
    setSaving(true)
    try {
      let avatar: string | undefined
      if (avatarPath) {
        const file = await uploadFile(avatarPath, 'avatar')
        avatar = file.url || `/api/file/download/${file.fileId}`
      }
      await userApi.updateProfile({ nickname: nickname.trim(), avatar })
      const latest = await authApi.me()
      setUser(latest)
      Taro.showToast({ title: '资料已保存', icon: 'success' })
      setTimeout(() => Taro.navigateBack(), 500)
    } catch (error) { console.error('[Profile] save failed', error); showError(error, '资料保存失败') } finally { setSaving(false) }
  }
  return <View className={styles.page}><View className={styles.avatarSection}><View className={styles.avatarWrap}>{avatarPath ? <Image className={styles.preview} src={avatarPath} mode='aspectFill' /> : <Avatar name={user?.nickname} url={user?.avatar} size={176} />}</View><Text className={styles.edit} onClick={() => void choose()}>更换头像</Text></View><View className={styles.card}><Text className={styles.label}>昵称</Text><Input className={styles.input} maxlength={20} value={nickname} onInput={(event) => setNickname(event.detail.value)} placeholder='请输入昵称' placeholderClass='placeholder' /><View className={styles.readonly}>登录账号：@{user?.username || '-'}</View><View className={styles.readonly}>用户 ID：{user?.id || '-'}</View></View><Button className={styles.save} loading={saving} disabled={saving} onClick={() => void save()}>保存资料</Button><Text className={styles.tip}>头像将上传至当前服务器，昵称会同步给好友与群成员</Text></View>
}
