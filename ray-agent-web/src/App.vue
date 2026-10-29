<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { streamChat } from './api/chat.js'
import RunTracePanel from './components/RunTracePanel.vue'
import ToolExecutionCard from './components/ToolExecutionCard.vue'

const message = ref('')
const liveAnswer = ref('')
const status = ref('')
const loading = ref(false)
const channels = ref([])
const channelId = ref('')
const sessions = ref([])
const sessionId = ref('')
const messages = ref([])
const runs = ref([])
const events = ref([])
const currentRun = ref(null)
let controller = null
let poll = null
let disposed = false

async function json(url, options) {
  const response = await fetch(url, options)
  if (!response.ok) throw new Error(`操作失败（HTTP ${response.status}）`)
  return response.json()
}

async function refresh() {
  sessions.value = await json('/api/sessions')
  if (!sessionId.value) return
  messages.value = await json(`/api/sessions/${sessionId.value}/messages`)
  runs.value = await json(`/api/sessions/${sessionId.value}/runs`)
  currentRun.value = runs.value[0] || null
  events.value = currentRun.value ? await json(`/api/runs/${currentRun.value.id}/events`) : []
}

async function viewRun(event) {
  currentRun.value = runs.value.find(run => run.id === event.target.value) || null
  events.value = currentRun.value ? await json(`/api/runs/${currentRun.value.id}/events`) : []
}

async function selectSession() {
  localStorage.setItem('ray-session', sessionId.value)
  liveAnswer.value = ''
  events.value = []
  status.value = ''
  await refresh()
}

async function createSession() {
  const session = await json('/api/sessions', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: '新会话' }),
  })
  sessionId.value = session.id
  await selectSession()
}

onMounted(async () => {
  try {
    channels.value = await json('/api/model-channels')
    channelId.value = channels.value[0]?.id || ''
    sessions.value = await json('/api/sessions')
    const saved = localStorage.getItem('ray-session')
    sessionId.value = sessions.value.find(session => session.id === saved)?.id || sessions.value[0]?.id || ''
    if (sessionId.value) await refresh()
    if (!channelId.value) status.value = '模型尚未配置，请先配置后端。'
    // 刷新页面后继续查询活动运行的状态；不声称能无损接回原来的 token 流。
    poll = setInterval(async () => {
      if (!loading.value && runs.value.some(run => run.status === 'RUNNING')) {
        try { await refresh() } catch { status.value = '无法刷新运行状态' }
      }
    }, 1000)
  } catch {
    status.value = '无法读取会话，请检查后端和数据库是否启动。'
  }
})

async function submit() {
  const text = message.value.trim()
  if (!text || loading.value) return
  loading.value = true
  liveAnswer.value = ''
  status.value = '正在生成…'
  controller = new AbortController()
  try {
    if (!sessionId.value) await createSession()
    const reason = await streamChat(text, controller.signal, chunk => {
      liveAnswer.value += chunk
    }, channelId.value, {
      sessionId: sessionId.value,
      clientRequestId: crypto.randomUUID(),
      onMeta: meta => {
        events.value = []
        currentRun.value = { id: meta.runId, status: 'RUNNING', channelId: channelId.value }
      },
      onTool: event => {
        events.value.push(event)
        status.value = event.type === 'MODEL_STARTED' ? '正在调用模型…'
          : event.type === 'TOOL_STARTED' ? '正在执行网页工具…'
          : event.type === 'TOOL_FAILED' ? '网页工具失败，模型正在处理结果…' : '正在继续回答…'
      },
    })
    status.value = reason === 'STOP' ? '回答完成' : `本次运行结束：${reason}`
    message.value = ''
  } catch (error) {
    if (!disposed) status.value = error.name === 'AbortError' ? '已停止接收' : (error.message || '请求失败')
  } finally {
    controller = null
    if (!disposed) {
      try { await refresh(); liveAnswer.value = '' } catch { status.value = '无法刷新历史，请检查后端' }
    }
    loading.value = false
  }
}

async function stop() {
  try {
    const running = loading.value ? currentRun.value : runs.value.find(run => run.status === 'RUNNING')
    if (running?.id) {
      await json(`/api/runs/${running.id}/cancel`, { method: 'POST' })
    }
    controller?.abort()
    status.value = '已请求取消'
    if (!loading.value) await refresh()
  } catch {
    status.value = '取消请求未确认，请刷新运行状态'
  }
}

onUnmounted(() => {
  disposed = true
  clearInterval(poll)
  controller?.abort()
})
</script>

<template>
  <main>
    <h1>RayAgent V2</h1>
    <p>CHAT 会话 · 历史由服务端保存</p>
    <label for="session">会话</label>
    <select id="session" v-model="sessionId" :disabled="loading" @change="selectSession">
      <option v-for="session in sessions" :key="session.id" :value="session.id">{{ session.title }} · {{ session.id.slice(0, 8) }}</option>
    </select>
    <button :disabled="loading" @click="createSession">新建会话</button>
    <section aria-label="历史消息">
      <article v-for="item in messages" :key="item.id">
        <strong>{{ item.role === 'USER' ? '你' : '助手' }}{{ item.complete ? '' : '（未完成）' }}</strong>
        <p class="answer">{{ item.content }}</p>
      </article>
      <p v-if="loading" class="answer live-answer">{{ liveAnswer }}</p>
    </section>
    <form @submit.prevent="submit">
      <label for="channel">模型渠道</label>
      <select id="channel" v-model="channelId" :disabled="loading || !channels.length">
        <option v-for="channel in channels" :key="channel.id" :value="channel.id">{{ channel.id }} · {{ channel.model }}</option>
      </select>
      <label for="message">你的消息</label>
      <textarea id="message" v-model="message" maxlength="4000" :disabled="loading" rows="4" />
      <div>
        <button type="submit" :disabled="loading || !message.trim() || !channelId || runs.some(run => run.status === 'RUNNING')">发送</button>
        <button v-if="loading || runs.some(run => run.status === 'RUNNING')" type="button" @click="stop">停止</button>
      </div>
    </form>
    <p role="status">{{ status }}</p>
    <label v-if="runs.length" for="trace-run">查看运行轨迹</label>
    <select v-if="runs.length" id="trace-run" :value="currentRun?.id" :disabled="loading" @change="viewRun">
      <option v-for="run in runs" :key="run.id" :value="run.id">{{ run.id.slice(0, 8) }} · {{ run.status }}</option>
    </select>
    <ToolExecutionCard :events="events" :status="currentRun?.status" />
    <RunTracePanel :run="currentRun" :events="events" />
  </main>
</template>

<style scoped>
main { max-width: 800px; margin: 2rem auto; padding: 0 1rem; font-family: sans-serif; }
label { display: block; margin: .5rem 0; }
select { margin-bottom: .5rem; max-width: 100%; }
textarea { box-sizing: border-box; width: 100%; font: inherit; }
button { margin: .5rem .5rem .5rem 0; padding: .5rem 1rem; }
.answer { white-space: pre-wrap; overflow-wrap: anywhere; }
article { border-bottom: 1px solid #ddd; padding-top: 1rem; }
</style>
