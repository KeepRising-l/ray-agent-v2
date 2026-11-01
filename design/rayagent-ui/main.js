import { createApp } from 'vue'
import Preview from './Preview.vue'
import './style.css'

// 与真实应用使用不同的存储键；设计预览不接触个人会话和后端凭据。
try { document.documentElement.dataset.theme = localStorage.getItem('ray-design-theme') === 'dark' ? 'dark' : 'light' } catch { /* 无本地存储时使用浅色。 */ }
createApp(Preview).mount('#app')
