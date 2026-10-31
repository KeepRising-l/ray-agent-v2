<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
const emit = defineEmits(['open-session'])
const tasks = ref([])
const capacity = ref(null)
const message = ref('')
const attempts = ref([])
let timer
let disposed = false
let refreshing = false

async function api(path, method = 'GET') {
  const response = await fetch(path, { method })
  if (!response.ok) throw new Error(`操作失败（HTTP ${response.status}）`)
  return response.json()
}
async function refresh() {
  if (refreshing) return
  refreshing = true
  try {
    const [list, limits] = await Promise.all([api('/api/tasks'), api('/api/tasks/capacity')])
    if (!disposed) { tasks.value = list; capacity.value = limits }
  } catch (error) { if (!disposed) message.value = error.message }
  finally { refreshing = false }
}
async function cancel(task) {
  try { await api(`/api/runs/${task.id}/cancel`, 'POST'); message.value = '已请求取消'; await refresh() }
  catch (error) { message.value = error.message }
}
async function retry(task) {
  try {
    await api(`/api/knowledge-bases/${task.kbId}/documents/${task.documentId}/versions/${task.id}/retry`, 'POST')
    message.value = '已重新排入文档处理队列'
    await refresh()
  } catch (error) { message.value = error.message }
}
async function inspect(task) {
  try { attempts.value = await api(`/api/tasks/documents/${task.id}/attempts`) }
  catch (error) { message.value = error.message }
}
onMounted(() => { refresh(); timer = setInterval(refresh, 1500) })
onUnmounted(() => { disposed = true; clearInterval(timer) })
</script>

<template>
  <section aria-label="任务与运行">
    <h2>任务与运行</h2>
    <p v-if="capacity">执行中 {{ capacity.running }}/{{ capacity.runningLimit }} · 排队 {{ capacity.queued }}/{{ capacity.queueLimit }} · 本进程过载拒绝 {{ capacity.rejected }} 次</p>
    <p>排队表示已接收，完成后才会显示成功；等待用户的运行可以回到原会话补充信息。</p>
    <p role="status">{{ message }}</p>
    <article v-for="task in tasks" :key="task.kind + task.id" class="task-card" :data-status="task.status">
      <strong>{{ task.kind === 'RUN' ? '对话运行' : '文档入库' }} · {{ task.status }}</strong>
      <p>{{ task.id }}</p>
      <p v-if="task.kind === 'RUN'">第 {{ task.attempt }} 个调度段 · {{ task.attached ? '当前进程有活动连接' : '持久历史或等待检查点' }}</p>
      <p v-else>尝试 {{ task.attempt }} 次 · 阶段 {{ task.stage }} · 本轮自动重试 {{ task.retries }} 次 {{ task.code || '' }}</p>
      <p v-if="task.nextAttemptAt">计划重试：{{ new Date(task.nextAttemptAt).toLocaleString() }}</p>
      <button v-if="task.sessionId" @click="emit('open-session', task.sessionId)">{{ task.status === 'WAITING_USER' ? '回到会话补充' : '查看会话' }}</button>
      <button v-if="task.kind === 'RUN' && ['QUEUED', 'RUNNING', 'WAITING_USER'].includes(task.status)" @click="cancel(task)">取消运行</button>
      <button v-if="task.recovery === 'RETRY_DOCUMENT'" @click="retry(task)">重试文档</button>
      <button v-if="task.kind === 'DOCUMENT'" @click="inspect(task)">查看尝试记录</button>
      <p v-if="task.kind === 'RUN' && task.status === 'INTERRUPTED'">进程中断；可查看历史后重新提问，本次运行不会自动重放。</p>
    </article>
    <details v-if="attempts.length" open>
      <summary>文档尝试记录</summary>
      <p v-for="item in attempts" :key="item.attempt">#{{ item.attempt }} · {{ item.stage }} · {{ item.status }}<span v-if="item.error_code"> · {{ item.error_code }}</span></p>
    </details>
  </section>
</template>

<style scoped>
.task-card { border: 1px solid #ddd; padding: .8rem; margin: .7rem 0; overflow-wrap: anywhere; }
button { margin-right: .6rem; }
</style>
