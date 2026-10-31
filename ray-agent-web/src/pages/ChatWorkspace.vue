<script setup>
import { computed, onMounted, onUnmounted, ref, nextTick } from 'vue'
import { createChatSession } from '../stores/chatSession.js'
import SessionList from '../components/SessionList.vue'
import ModeBadge from '../components/ModeBadge.vue'
import ChatModeSelector from '../components/ChatModeSelector.vue'
import KnowledgeSelector from '../components/KnowledgeSelector.vue'
import PendingQuestionPanel from '../components/PendingQuestionPanel.vue'
import ToolExecutionCard from '../components/ToolExecutionCard.vue'
import RouteDecisionPanel from '../components/RouteDecisionPanel.vue'
import CitationPanel from '../components/CitationPanel.vue'
import RunTracePanel from '../components/RunTracePanel.vue'
import RunActions from '../components/RunActions.vue'
import RunErrorNotice from '../components/RunErrorNotice.vue'
import MessageContent from '../components/MessageContent.vue'
const chat = createChatSession()
const s = chat.state
const scope = computed(() => (chat.selected.value?.knowledgeBaseIds || []).map(id => s.bases.find(base => base.id === id)?.name || '已不可用的资料范围'))
const failed = computed(() => ['FAILED', 'INTERRUPTED', 'TIMED_OUT', 'CANCELLED'].includes(s.currentRun?.status))
const citations = ref(null)
const sourceArea = ref(null)
async function showSource(runId, id) {
  await chat.viewRun(runId)
  await nextTick()
  if (s.currentRun?.id !== runId) return
  if (id.startsWith('K')) {
    const found = await citations.value?.openCitation(id)
    if (!found) { s.status = '该回答没有记录此资料引用。'; return }
  } else {
    const element = sourceArea.value?.querySelector(`[data-web-source="${id}"]`)
    if (!element) { s.status = '该回答没有记录此网页来源。'; return }
    element.focus()
  }
  await nextTick()
  sourceArea.value?.scrollIntoView({ block: 'start', behavior: media.matches && !matchMedia('(prefers-reduced-motion: reduce)').matches ? 'smooth' : 'instant' })
}
let timer
let disposed = false
const wide = ref(globalThis.matchMedia('(min-width: 761px)').matches)
const media = globalThis.matchMedia('(min-width: 761px)')
const resize = event => { wide.value = event.matches }
onMounted(async () => { media.addEventListener('change', resize); await chat.init(); if (!disposed) timer = setInterval(chat.poll, 1000) })
onUnmounted(() => { disposed = true; clearInterval(timer); media.removeEventListener('change', resize); chat.dispose() })
defineExpose({ openSession: chat.select, refresh: chat.probe })
</script>
<template>
  <div class="chat-workspace">
    <aside class="session-sidebar">
      <SessionList :sessions="s.sessions" :selected="s.sessionId" :disabled="s.creating" @select="chat.select" />
      <details :open="wide || !s.sessions.length" class="new-session"><summary>新建会话</summary>
        <label for="session-title">名称（可选）</label><input id="session-title" v-model="s.title" maxlength="80" :disabled="s.creating" />
        <ChatModeSelector v-model="s.newMode" :disabled="s.creating" />
        <KnowledgeSelector v-if="s.newMode !== 'CHAT'" v-model="s.newBases" :bases="s.bases" :disabled="s.creating" />
        <p class="help">此处只设置新会话；已有会话的模式与资料范围保持固定。</p>
        <button type="button" :disabled="s.creating || (s.newMode === 'KNOWLEDGE' && !s.newBases.length)" @click="chat.create">新建会话</button>
      </details>
    </aside>
    <section class="conversation" aria-label="聊天工作区" :aria-busy="s.fetching">
      <header><h2>{{ chat.selected.value?.title || '开始一次对话' }}</h2><ModeBadge :mode="chat.selected.value?.mode || s.newMode" />
        <p v-if="scope.length" class="help">当前资料：{{ scope.join('、') }}</p>
        <p v-else-if="chat.selected.value && chat.selected.value.mode !== 'CHAT'" class="help">未选择知识库范围。</p>
      </header>
      <p v-if="s.fetching" role="status">正在读取会话…</p>
      <section aria-label="历史消息" class="messages">
        <p v-if="!s.messages.length && !s.fetching" class="help">发送问题后，回答和引用会保存在当前会话。</p>
        <article v-for="item in s.messages" :key="item.id" class="message"><strong>{{ item.role === 'USER' ? '你' : '助手' }}{{ item.complete ? '' : '（未完成）' }}</strong><MessageContent :content="item.content" :sources="item.role === 'ASSISTANT' && !!item.runId" :disabled="chat.busy.value" @source="id => showSource(item.runId, id)" /></article>
        <p v-if="chat.liveAnswer.value" class="answer live-answer">{{ chat.liveAnswer.value }}</p>
      </section>
      <button v-if="chat.active.value && chat.active.value.id !== s.currentRun?.id" @click="chat.viewRun(chat.active.value.id)">查看活动运行</button>
      <PendingQuestionPanel :question="s.question" :disabled="chat.busy.value" @answer="chat.answer" />
      <RunErrorNotice :error="s.error" :run="s.currentRun" />
      <form class="composer" @submit.prevent="chat.submit">
        <label for="channel">模型渠道</label>
        <select id="channel" v-model="s.channelId" :disabled="s.creating || chat.busy.value || !s.channels.length || s.error?.uncertain"><option v-for="channel in s.channels" :key="channel.id" :value="channel.id">{{ channel.id }} · {{ channel.model }}</option></select>
        <label for="message">你的消息</label>
        <textarea id="message" v-model="s.draft" maxlength="4000" rows="4" :disabled="s.creating || s.fetching || chat.busy.value || chat.active.value?.status === 'WAITING_USER' || s.error?.uncertain" />
        <div class="composer-actions"><button type="submit" class="primary" :disabled="!chat.canSend.value">发送</button>
          <RunActions :active="chat.active.value" :busy="chat.busy.value" :failed="failed" :uncertain="s.error?.uncertain" @cancel="chat.cancel" @refresh="chat.probe" @edit="chat.editLast" />
        </div>
      </form>
      <p role="status" class="chat-status">{{ s.status }}</p>
      <label v-if="s.runs.length" for="trace-run">查看运行轨迹</label>
      <select v-if="s.runs.length" id="trace-run" :value="s.currentRun?.id" :disabled="chat.busy.value" @change="chat.viewRun($event.target.value)"><option v-for="run in s.runs" :key="run.id" :value="run.id">{{ run.id.slice(0, 8) }} · {{ run.status }}</option></select>
      <div ref="sourceArea"><ToolExecutionCard :events="s.events" :status="s.currentRun?.status" /><RouteDecisionPanel :events="s.events" />
        <CitationPanel ref="citations" :run="s.currentRun" :events="s.events" />
      </div>
      <RunTracePanel :run="s.currentRun" :events="s.events" />
    </section>
  </div>
</template>
<style scoped>
.chat-workspace { display: grid; grid-template-columns: minmax(210px, 270px) minmax(0, 1fr); gap: 2rem; animation: appear .16s ease-out; }
.session-sidebar { border-right: 1px solid #ddd; padding-right: 1.5rem; min-width: 0; }.conversation { min-width: 0; }
.new-session { margin-top: 1.5rem; }.help { color: #575c63; font-size: .94rem; }.messages { min-height: 7rem; margin: 1.5rem 0; }
.message { border-bottom: 1px solid #ddd; padding: .8rem 0; animation: appear .15s ease-out; }.answer { white-space: pre-wrap; overflow-wrap: anywhere; }
.composer-actions { display: flex; gap: .5rem; flex-wrap: wrap; align-items: center; }.chat-status { min-height: 1.6em; }
@keyframes appear { from { opacity: .5; transform: translateY(3px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 760px) { .chat-workspace { grid-template-columns: minmax(0, 1fr); gap: 1rem; }.session-sidebar { border-right: 0; border-bottom: 1px solid #ddd; padding: 0 0 1rem; } }
@media (prefers-reduced-motion: reduce) { .message, .chat-workspace { animation: none; } }
</style>
