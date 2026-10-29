import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    // 开发时由 Vite 转发后端请求；目标可以通过环境变量调整。
    proxy: { '/api': process.env.RAY_DEV_API_TARGET || 'http://127.0.0.1:8080' },
  },
})
