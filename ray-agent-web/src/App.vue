<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { streamChat, streamAnswer } from './api/chat.js'
import RunTracePanel from './components/RunTracePanel.vue'
import ToolExecutionCard from './components/ToolExecutionCard.vue'
import KnowledgePanel from './components/KnowledgePanel.vue'
import KnowledgeSelector from './components/KnowledgeSelector.vue'
import CitationPanel from './components/CitationPanel.vue'
import ChatModeSelector from './components/ChatModeSelector.vue'
import RouteDecisionPanel from './components/RouteDecisionPanel.vue'
import PendingQuestionPanel from './components/PendingQuestionPanel.vue'
import TaskPanel from './components/TaskPanel.vue'

const view = ref('chat')
const newMode = ref('CHAT')
const selectedKnowledgeBases = ref([])
const knowledgeBases = ref([])
const sessionMode = ref('CHAT')
const ragRefused = ref(false)

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
const pendingQuestion = ref(null)
const hasWaiting = computed(() => runs.value.some(run => run.status === 'WAITING_USER'))
let controller = null
let poll = null
let disposed = false

async function openChat() {
  view.value = 'chat'
  try { knowledgeBases.value = await json('/api/knowledge-bases') } catch { status.value = '无法读取知识库' }
}

async function openTaskSession(id) {
  sessionId.value = id
  await openChat()
  await selectSession()
}

async function json(url, options) {
  const response = await fetch(url, options)
  if (!response.ok) throw new Error(`操作失败（HTTP ${response.status}）`)
  return response.json()
}

async function refresh() {
  sessions.value = await json('/api/sessions')
  knowledgeBases.value = await json('/api/knowledge-bases')
  if (!sessionId.value) return
  sessionMode.value = sessions.value.find(session => session.id === sessionId.value)?.mode || 'CHAT'
  messages.value = await json(`/api/sessions/${sessionId.value}/messages`)
  runs.value = await json(`/api/sessions/${sessionId.value}/runs`)
  currentRun.value = runs.value[0] || null
  events.value = currentRun.value ? await json(`/api/runs/${currentRun.value.id}/events`) : []
  pendingQuestion.value = currentRun.value ? (await json(`/api/runs/${currentRun.value.id}/question`)).question : null
}

async function viewRun(event) {
  currentRun.value = runs.value.find(run => run.id === event.target.value) || null
  events.value = currentRun.value ? await json(`/api/runs/${currentRun.value.id}/events`) : []
  pendingQuestion.value = currentRun.value ? (await json(`/api/runs/${currentRun.value.id}/question`)).question : null
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
    body: JSON.stringify({ title: newMode.value === 'KNOWLEDGE' ? '知识库会话' : newMode.value === 'AUTO' ? 'AUTO会话' : '新会话', mode: newMode.value,
      knowledgeBaseIds: newMode.value !== 'CHAT' ? selectedKnowledgeBases.value : [] }),
  })
  sessionId.value = session.id
  await selectSession()
}

onMounted(async () => {
  try {
    channels.value = await json('/api/model-channels')
    channelId.value = channels.value[0]?.id || ''
    sessions.value = await json('/api/sessions')
    knowledgeBases.value = await json('/api/knowledge-bases')
    const saved = localStorage.getItem('ray-session')
    sessionId.value = sessions.value.find(session => session.id === saved)?.id || sessions.value[0]?.id || ''
    if (sessionId.value) await refresh()
    if (!channelId.value) status.value = '模型尚未配置，请先配置后端。'
    // 刷新页面后继续查询活动运行的状态；不声称能无损接回原来的 token 流。
    poll = setInterval(async () => {
      if (!loading.value && runs.value.some(run => ['QUEUED', 'RUNNING', 'WAITING_USER'].includes(run.status))) {
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
  ragRefused.value = false
  status.value = '正在提交…'
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
        currentRun.value = { id: meta.runId, status: meta.status || 'QUEUED', channelId: channelId.value }
      },
      onTool: event => {
        events.value.push(event)
        status.value = event.type === 'MODEL_STARTED' ? '正在调用模型…'
          : event.type === 'TOOL_STARTED' ? '正在执行网页工具…'
          : event.type === 'TOOL_FAILED' ? '网页工具失败，模型正在处理结果…' : '正在继续回答…'
      },
      onQueue: value => {
        currentRun.value = { ...currentRun.value, status: value.status }
        status.value = value.status === 'QUEUED' ? '已排队，等待执行槽…' : '正在执行…'
      },
      onQuestion: question => { pendingQuestion.value = question },
      onContext: event => {
        events.value.push(event)
        if (event.type === 'COMPACTION_STARTED') status.value = '正在整理较早的对话历史…'
      },
      onRoute: event => {
        events.value.push(event)
        status.value = event.type === 'ROUTE_DECIDED' ? '已确定回答路径…'
          : event.type === 'HYBRID_STARTED' ? '正在组合资料与网页…' : '正在处理AUTO请求…'
      },
      onRag: event => {
        events.value.push(event)
        if (event.type === 'RAG_REFUSED') ragRefused.value = true
        status.value = event.type === 'RAG_RETRIEVAL_STARTED' ? '正在检索所选知识库…'
          : event.type === 'RAG_JUDGE_STARTED' ? '正在核对资料是否足够…'
          : event.type === 'RAG_REFUSED' ? '资料依据不足' : '正在根据资料回答…'
      },
    })
    status.value = reason === 'WAITING_USER' ? '等待你补充信息，运行已暂停' : ragRefused.value ? '资料依据不足' : reason === 'STOP' ? '回答完成' : `本次运行结束：${reason}`
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

async function answerQuestion(answer) {
  const question = pendingQuestion.value
  if (!question || loading.value) return
  loading.value = true
  liveAnswer.value = ''
  controller = new AbortController()
  status.value = '正在继续原运行…'
  try {
    const reason = await streamAnswer(question.runId, question.id, answer, controller.signal, text => { liveAnswer.value += text }, {
      onMeta: meta => { currentRun.value = { ...currentRun.value, id: meta.runId, status: meta.status || 'QUEUED' } },
      onTool: event => { events.value.push(event) },
      onRag: event => { events.value.push(event) },
      onRoute: event => { events.value.push(event) },
      onContext: event => { events.value.push(event) },
      onQueue: value => {
        currentRun.value = { ...currentRun.value, status: value.status }
        status.value = value.status === 'QUEUED' ? '已排队，等待执行槽…' : '正在继续原运行…'
      },
      onQuestion: value => { pendingQuestion.value = value },
    })
    status.value = reason === 'WAITING_USER' ? '等待下一项补充信息' : '回答完成'
  } catch (error) {
    status.value = error.name === 'AbortError' ? '已停止接收' : error.message
  } finally {
    controller = null
    try { await refresh(); liveAnswer.value = '' } catch { status.value = '无法刷新运行状态' }
    loading.value = false
  }
}

async function stop() {
  try {
    const running = loading.value ? currentRun.value : runs.value.find(run => ['QUEUED', 'RUNNING', 'WAITING_USER'].includes(run.status))
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
    <nav aria-label="功能切换">
      <button @click="openChat">聊天</button>
      <button @click="view = 'knowledge'">知识库管理</button>
      <button @click="view = 'tasks'">任务与运行</button>
    </nav>
    <KnowledgePanel v-if="view === 'knowledge'" />
    <TaskPanel v-if="view === 'tasks'" @open-session="openTaskSession" />
    <div v-show="view === 'chat'">
    <p>当前会话模式：{{ sessionMode }} · 历史由服务端保存</p>
    <ChatModeSelector v-model="newMode" :disabled="loading" />
    <KnowledgeSelector v-if="newMode !== 'CHAT'" v-model="selectedKnowledgeBases" :bases="knowledgeBases" :disabled="loading" />
    <p v-if="newMode === 'AUTO'">AUTO 可选 0～5 个知识库；未选择时只对话、联网或请求补充资料范围。</p>
    <p>选择模式和资料范围后点击“新建会话”；已有会话的模式与知识库范围保持固定。</p>
    <label for="session">会话</label>
    <select id="session" v-model="sessionId" :disabled="loading" @change="selectSession">
      <option v-for="session in sessions" :key="session.id" :value="session.id">{{ session.title }} · {{ session.id.slice(0, 8) }}</option>
    </select>
    <button :disabled="loading || (newMode === 'KNOWLEDGE' && !selectedKnowledgeBases.length)" @click="createSession">新建会话</button>
    <section aria-label="历史消息">
      <article v-for="item in messages" :key="item.id">
        <strong>{{ item.role === 'USER' ? '你' : '助手' }}{{ item.complete ? '' : '（未完成）' }}</strong>
        <p class="answer">{{ item.content }}</p>
      </article>
      <p v-if="loading" class="answer live-answer">{{ liveAnswer }}</p>
    </section>
    <PendingQuestionPanel :question="pendingQuestion" :disabled="loading" @answer="answerQuestion" />
    <form @submit.prevent="submit">
      <label for="channel">模型渠道</label>
      <select id="channel" v-model="channelId" :disabled="loading || !channels.length">
        <option v-for="channel in channels" :key="channel.id" :value="channel.id">{{ channel.id }} · {{ channel.model }}</option>
      </select>
      <label for="message">你的消息</label>
      <textarea id="message" v-model="message" maxlength="4000" :disabled="loading || hasWaiting" rows="4" />
      <div>
        <button type="submit" :disabled="loading || hasWaiting || !message.trim() || !channelId || runs.some(run => ['QUEUED', 'RUNNING', 'WAITING_USER'].includes(run.status))">发送</button>
        <button v-if="loading || runs.some(run => ['QUEUED', 'RUNNING', 'WAITING_USER'].includes(run.status))" type="button" @click="stop">停止</button>
      </div>
    </form>
    <p role="status">{{ status }}</p>
    <label v-if="runs.length" for="trace-run">查看运行轨迹</label>
    <select v-if="runs.length" id="trace-run" :value="currentRun?.id" :disabled="loading" @change="viewRun">
      <option v-for="run in runs" :key="run.id" :value="run.id">{{ run.id.slice(0, 8) }} · {{ run.status }}</option>
    </select>
    <ToolExecutionCard :events="events" :status="currentRun?.status" />
    <RouteDecisionPanel :events="events" />
    <CitationPanel :run="currentRun" :events="events" />
    <RunTracePanel :run="currentRun" :events="events" />
    </div>
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
