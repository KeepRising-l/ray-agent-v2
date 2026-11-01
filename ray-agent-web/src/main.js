import { createApp } from 'vue'
import App from './App.vue'

// 只恢复外观偏好，避免首次绘制时在浅色和深色间闪动。
try { document.documentElement.dataset.theme = localStorage.getItem('ray-ui-theme') === 'dark' ? 'dark' : 'light' } catch { document.documentElement.dataset.theme = 'light' }

// 将根组件渲染到 index.html 的 #app 容器。
createApp(App).mount('#app')
