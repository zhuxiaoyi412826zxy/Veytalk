export default defineAppConfig({
  pages: [
    'pages/index/index',
    'pages/login/index',
    'pages/register/index',
    'pages/conversations/index',
    'pages/friends/index',
    'pages/videos/index',
    'pages/ai/index',
    'pages/profile/index',
    'pages/chat/index',
    'pages/friend-requests/index',
    'pages/add-friend/index',
    'pages/groups/index',
    'pages/group-create/index',
    'pages/group-detail/index',
    'pages/live/index',
    'pages/live-room/index',
    'pages/scan/index',
    'pages/profile-edit/index',
    'pages/settings/index',
    'pages/article/index'
  ],
  window: {
    backgroundTextStyle: 'dark',
    backgroundColor: '#f5f6f7',
    navigationBarBackgroundColor: '#ffffff',
    navigationBarTitleText: 'VeyTalk',
    navigationBarTextStyle: 'black'
  },
  tabBar: {
    color: '#86909c',
    selectedColor: '#4f6bfe',
    backgroundColor: '#ffffff',
    borderStyle: 'white',
    list: [
      { pagePath: 'pages/conversations/index', text: '消息', iconPath: 'assets/tabbar/message.svg', selectedIconPath: 'assets/tabbar/message-active.svg' },
      { pagePath: 'pages/friends/index', text: '好友', iconPath: 'assets/tabbar/friends.svg', selectedIconPath: 'assets/tabbar/friends-active.svg' },
      { pagePath: 'pages/videos/index', text: '视频', iconPath: 'assets/tabbar/video.svg', selectedIconPath: 'assets/tabbar/video-active.svg' },
      { pagePath: 'pages/ai/index', text: 'AI', iconPath: 'assets/tabbar/ai.svg', selectedIconPath: 'assets/tabbar/ai-active.svg' },
      { pagePath: 'pages/profile/index', text: '我的', iconPath: 'assets/tabbar/profile.svg', selectedIconPath: 'assets/tabbar/profile-active.svg' }
    ]
  },
  permission: {
    'scope.camera': { desc: '用于扫描网页登录二维码' }
  }
})
