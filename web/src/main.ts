import { createPinia } from 'pinia'
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import App from './App.vue'
import router from './router'
import { useThemeStore } from './stores/theme'
import { installPressSpring } from './utils/pressSpring'

import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/base.css'
import './styles/theme.css'
import './styles/element.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
useThemeStore().init()

app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 按钮按压回弹：全局委托一次，所有按钮都有反馈
installPressSpring()

app.mount('#app')
