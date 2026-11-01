<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import ModeBadge from '../components/ModeBadge.vue'
import PendingQuestionPanel from '../components/PendingQuestionPanel.vue'
import ToolExecutionCard from '../components/ToolExecutionCard.vue'
import RouteDecisionPanel from '../components/RouteDecisionPanel.vue'
import CitationPanel from '../components/CitationPanel.vue'
import RunTracePanel from '../components/RunTracePanel.vue'
import RunActions from '../components/RunActions.vue'
import RunErrorNotice from '../components/RunErrorNotice.vue'
import MessageContent from '../components/MessageContent.vue'
import UiIcon from '../components/UiIcon.vue'
import BrandMark from '../components/BrandMark.vue'
const props = defineProps({ chat: { type: Object, required: true }, user: String, theme: { type: String, default: 'light' } })
const emit = defineEmits(['open-nav', 'toggle-theme', 'pick-mode'])
const modes = {
  CHAT: { name: '智能对话', icon: 'chat', detail: '自由对话，需要时联网，不读取知识库。' },
  KNOWLEDGE: { name: '知识库问答', icon: 'book', detail: '只依据所选资料回答，提供可核对的引用。' },
  AUTO: { name: '自动识别', icon: 'settings', detail: '按问题选择资料或联网，分别标明来源。' },
}
const modeOpen = ref(false)
const currentMode = computed(() => chat.selected.value?.mode || s.newMode || 'CHAT')
const modeInfo = computed(() => modes[currentMode.value] || modes.CHAT)
// 模式属于会话级设置：选新模式会打开新建会话对话框并预选，不修改当前会话。
function pickMode(id) { modeOpen.value = false; emit('pick-mode', id) }
const chat = props.chat
const s = chat.state
const scope = computed(() => (chat.selected.value?.knowledgeBaseIds || []).map(id => s.bases.find(base => base.id === id)?.name || '已不可用的资料范围'))
const failed = computed(() => ['FAILED', 'INTERRUPTED', 'TIMED_OUT', 'CANCELLED'].includes(s.currentRun?.status))
const empty = computed(() => !s.messages.length && !chat.liveAnswer.value && !s.fetching)
const citations = ref(null)
const inspector = ref(null)
const sourceArea = ref(null)
const messageArea = ref(null)
const input = ref(null)
const follow = ref(true)
const inspectorOpen = ref(false)
function inspect() { if (!inspector.value.open) inspector.value.showModal(); inspectorOpen.value = true }
function trackScroll() {
  // 用户正在读上文时不强拉到底部；只有接近末尾才继续追随流式内容。
  const area = messageArea.value
  follow.value = area.scrollHeight-area.scrollTop-area.clientHeight < 110
}
async function bottom(force = false) {
  await nextTick()
  if (messageArea.value && (follow.value || force)) messageArea.value.scrollTop = messageArea.value.scrollHeight
}
async function grow() {
  await nextTick()
  if (!input.value) return
  input.value.style.height = 'auto'
  input.value.style.height = Math.min(192, input.value.scrollHeight) + 'px'
}
function onKey(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault()
    if (chat.canSend.value) chat.submit()
  }
}
function suggestion(text) { s.draft = text; input.value?.focus() }
async function showSource(runId, id) {
  // 来源属于这条回答的run，不能直接使用当前最新一次运行的同名K/W编号。
  await chat.viewRun(runId)
  await nextTick()
  if (s.currentRun?.id !== runId) return
  inspect()
  await nextTick()
  if (id.startsWith('K')) {
    const found = await citations.value?.openCitation(id)
    if (!found) { s.status = '该回答没有记录此资料引用。'; return }
  } else {
    const element = sourceArea.value?.querySelector(`[data-web-source="${id}"]`)
    if (!element) { s.status = '该回答没有记录此网页来源。'; return }
    element.focus()
  }
  sourceArea.value?.scrollIntoView({ block: 'start', behavior: 'instant' })
}
watch(() => s.sessionId, () => { follow.value = true; bottom(true) })
watch([() => s.messages.length, () => chat.liveAnswer.value, () => s.question], () => bottom())
watch(() => s.draft, grow, { immediate: true })
</script>

<template>
  <section class="conversation" aria-label="聊天工作区" :aria-busy="s.fetching">
    <header class="chat-topbar">
      <div class="chat-topbar-left"><button class="icon-button mobile-menu" aria-label="打开导航" @click="emit('open-nav')"><UiIcon name="menu"/></button><span class="page-name">{{ chat.selected.value?.title || '新的对话' }}</span></div>
      <div class="chat-topbar-right">
        <button class="theme-button" :aria-label="theme === 'light' ? '切换深色模式' : '切换浅色模式'" @click="emit('toggle-theme')"><UiIcon :name="theme === 'light' ? 'moon' : 'sun'" :size="17"/><span>{{ theme === 'light' ? '深色' : '浅色' }}</span></button>
        <div class="mode-control-wrap" @focusout="event => { if (!event.currentTarget.contains(event.relatedTarget)) modeOpen = false }">
          <button class="mode-button" aria-haspopup="menu" :aria-expanded="modeOpen" @click="modeOpen = !modeOpen"><UiIcon :name="modeInfo.icon" :size="17"/><span>{{ modeInfo.name }}</span><UiIcon name="chevron" :size="14" class="down"/></button>
          <div v-if="modeOpen" class="mode-menu" role="menu" aria-label="选择新对话模式">
            <p>在新对话中使用</p>
            <button v-for="(mode, id) in modes" :key="id" role="menuitemradio" :aria-checked="currentMode === id" @click="pickMode(id)"><span class="mode-icon"><UiIcon :name="mode.icon"/></span><span><strong>{{ mode.name }}</strong><small>{{ mode.detail }}</small></span><UiIcon v-if="currentMode === id" name="check" :size="17"/></button>
            <div class="menu-note">切换模式会开始新对话，已有会话保持原样。</div>
          </div>
        </div>
        <button class="inspect-button" aria-label="来源与运行" :aria-expanded="inspectorOpen" @click="inspect"><UiIcon name="panel" :size="18"/><span>来源与运行</span></button>
      </div>
    </header>
    <div class="conversation-context"><ModeBadge :mode="currentMode"/><p v-if="scope.length" class="scope-label" :title="scope.join('、')">资料：{{ scope.join('、') }}</p><p v-else-if="chat.selected.value && currentMode !== 'CHAT'" class="scope-label">未选择知识库范围</p></div>
    <div ref="messageArea" class="message-scroll" tabindex="0" role="region" aria-label="对话阅读区" @scroll="trackScroll">
      <div class="conversation-content" :class="{ 'is-empty': empty }">
        <p v-if="s.fetching" class="loading-message" role="status">正在读取会话…</p>
        <div v-if="empty" class="conversation-welcome"><div class="assistant-identity"><BrandMark/><span>RayAgent</span><span class="identity-dot"/></div><h1>你好，<span>{{ user || '朋友' }}</span></h1><p>今天想聊点什么？梳理一个想法，或从你的资料里找答案。</p><div class="suggestions"><button @click="suggestion('帮我梳理一下这个问题的思路')"><UiIcon name="chat" :size="17"/>读懂一个概念<UiIcon name="chevron" :size="13"/></button><button @click="suggestion('用一个具体例子解释这个概念')"><UiIcon name="book" :size="17"/>梳理一条链路<UiIcon name="chevron" :size="13"/></button></div></div>
        <section aria-label="历史消息" class="messages">
          <article v-for="item in s.messages" :key="item.id" class="message" :class="item.role === 'USER' ? 'message-user' : 'message-assistant'">
            <div class="message-label" :class="{ 'sr-only': item.role === 'USER' }"><BrandMark v-if="item.role !== 'USER'"/><span>{{ item.role === 'USER' ? '你' : 'RayAgent' }}</span><span v-if="!item.complete" class="incomplete-label">未完成</span></div>
            <div class="message-body"><MessageContent :content="item.content" :plain="item.role === 'USER'" :sources="item.role === 'ASSISTANT' && !!item.runId" :disabled="chat.busy.value" @source="id => showSource(item.runId, id)"/></div>
          </article>
          <article v-if="chat.liveAnswer.value" class="message message-assistant live-answer"><div class="message-label"><BrandMark/><span>RayAgent</span><span class="stream-dot" aria-label="正在输出"/></div><MessageContent :content="chat.liveAnswer.value"/></article>
        </section>
        <button v-if="chat.active.value && chat.active.value.id !== s.currentRun?.id" @click="chat.viewRun(chat.active.value.id)">查看活动运行</button>
        <PendingQuestionPanel :question="s.question" :disabled="chat.busy.value" @answer="chat.answer"/>
        <RunErrorNotice :error="s.error" :run="s.currentRun"/>
        <div v-if="s.currentRun?.status === 'QUEUED'" class="queue-notice"><span class="stream-dot"/><span>已接收并排队，尚未执行模型。</span></div>
      </div>
    </div>
    <div class="composer-dock"><div class="composer-width">
      <button v-if="!follow" class="scroll-bottom icon-button" aria-label="回到最新消息" @click="follow = true; bottom(true)"><UiIcon name="arrow"/></button>
      <form class="composer" @submit.prevent="chat.submit">
      <label for="message" class="sr-only">你的消息</label><textarea id="message" ref="input" v-model="s.draft" maxlength="4000" rows="1" :placeholder="currentMode === 'KNOWLEDGE' ? '向你的资料提问…' : '给 RayAgent 发送消息'" :disabled="s.creating || s.fetching || chat.busy.value || chat.active.value?.status === 'WAITING_USER' || s.error?.uncertain" @keydown="onKey"/>
        <div class="composer-footer"><div class="model-control"><label for="channel" class="sr-only">模型渠道</label><select id="channel" v-model="s.channelId" :disabled="s.creating || chat.busy.value || !s.channels.length || s.error?.uncertain" :title="s.channels.find(channel => channel.id === s.channelId)?.model"><option v-if="!s.channels.length" value="">尚未配置模型</option><option v-for="channel in s.channels" :key="channel.id" :value="channel.id">{{ channel.model }}</option></select></div><span class="composer-hint">Enter 发送 · Shift + Enter 换行</span><button type="submit" class="send-button" aria-label="发送" :disabled="!chat.canSend.value" title="发送"><UiIcon name="arrow"/></button></div>
      </form>
      <div class="composer-meta"><p role="status" class="chat-status">{{ s.status }}</p><RunActions :active="chat.active.value" :busy="chat.busy.value" :failed="failed" :uncertain="s.error?.uncertain" @cancel="chat.cancel" @refresh="chat.probe" @edit="chat.editLast"/></div>
      <p class="composer-disclaimer">回答可能不准确，重要信息请结合来源核实。</p>
    </div></div>
    <dialog ref="inspector" class="inspector-dialog" aria-labelledby="inspector-title" @close="inspectorOpen = false">
      <div class="inspector-heading"><div><h2 id="inspector-title">来源与运行</h2><p>查看依据与本次处理过程</p></div><button class="icon-button" aria-label="关闭来源与运行" @click="inspector.close()"><UiIcon name="close"/></button></div>
      <div class="inspector-body"><label v-if="s.runs.length" for="trace-run">查看运行轨迹</label><select v-if="s.runs.length" id="trace-run" :value="s.currentRun?.id" :disabled="chat.busy.value" @change="chat.viewRun($event.target.value)"><option v-for="run in s.runs" :key="run.id" :value="run.id">{{ run.id.slice(0, 8) }} · {{ run.status }}</option></select>
        <div ref="sourceArea"><ToolExecutionCard :events="s.events" :status="s.currentRun?.status"/><RouteDecisionPanel :events="s.events"/><CitationPanel ref="citations" :run="s.currentRun" :events="s.events"/></div>
        <RunTracePanel :run="s.currentRun" :events="s.events"/><p v-if="!s.runs.length" class="empty-inspector">发送一条消息后，可以在这里查看来源和运行记录。</p>
      </div>
    </dialog>
  </section>
</template>
