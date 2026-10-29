<script setup>
import { onUnmounted, ref } from 'vue'
import { streamChat } from './api/chat.js'

const message = ref('')
const answer = ref('')
const status = ref('')
const loading = ref(false)
let controller = null

// 发送一条独立消息；当前阶段不保存或传递历史会话。
async function submit() {
  const text = message.value.trim()
  if (!text || loading.value) return
  answer.value = ''
  status.value = '正在生成…'
  loading.value = true
  controller = new AbortController()
  try {
    const reason = await streamChat(text, controller.signal, chunk => {
      answer.value += chunk
    })
    status.value = reason === 'STOP' ? '回答完成' : `本次生成结束：${reason}`
  } catch (error) {
    status.value = error.name === 'AbortError' ? '已停止接收' : (error.message || '请求失败')
  } finally {
    loading.value = false
    controller = null
  }
}

function stop() {
  controller?.abort()
}

onUnmounted(stop)
</script>

<template>
  <main>
    <h1>RayAgent V2</h1>
    <p>最小聊天验证：每次请求独立，暂不保存会话。</p>
    <form @submit.prevent="submit">
      <label for="message">你的消息</label>
      <textarea id="message" v-model="message" maxlength="4000" :disabled="loading" rows="4" />
      <div>
        <button type="submit" :disabled="loading || !message.trim()">发送</button>
        <button v-if="loading" type="button" @click="stop">停止</button>
      </div>
    </form>
    <p role="status">{{ status }}</p>
    <section aria-label="模型回答">
      <h2>回答</h2>
      <p class="answer">{{ answer }}</p>
    </section>
  </main>
</template>

<style scoped>
main { max-width: 760px; margin: 3rem auto; padding: 0 1rem; font-family: sans-serif; }
label { display: block; margin-bottom: .5rem; }
textarea { box-sizing: border-box; width: 100%; font: inherit; }
button { margin: .75rem .5rem 0 0; padding: .5rem 1rem; }
.answer { white-space: pre-wrap; }
</style>
