<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import UiIcon from '../../ray-agent-web/src/components/UiIcon.vue'
import brand from './assets/brand.ico'

// 仅用于设计体验的自写内容。没有网络请求，也不会写入真实数据库。
const modeInfo = {
  CHAT: { name: '智能对话', icon: 'chat', detail: '自由对话，需要时联网，不读取知识库。' },
  KNOWLEDGE: { name: '知识库问答', icon: 'book', detail: '只依据所选资料回答，提供可核对的引用。' },
  AUTO: { name: '自动识别', icon: 'settings', detail: '按问题选择资料或联网，分别标明来源。' },
}
const sessions = ref([
  { id: 'notes', title: '上下文摘要与原始历史', mode: 'KNOWLEDGE', scope: ['RayAgent 设计笔记'], group: '今天' },
  { id: 'modes', title: '三种回答模式怎么选', mode: 'CHAT', scope: [], group: '今天' },
  { id: 'rag', title: '梳理 RAG 的完整链路', mode: 'AUTO', scope: ['RayAgent 设计笔记'], group: '昨天' },
])
const demoMessages = {
  notes: [
    { role: 'USER', text: '上下文摘要和原始历史有什么区别？' },
    { role: 'ASSISTANT', title: '历史负责留存，摘要帮助模型理解。', paragraphs: ['原始历史保存完整的用户问题和助手回答，供页面展示与后续核查。上下文摘要则把较早的对话整理成更短的投影，减少下一次请求占用的上下文。'], points: ['原始消息不会因为生成摘要而删除。', '摘要与近期消息一起进入模型输入。', '摘要是历史信息，不能代替当前知识库证据。'], ending: '可以把它理解为：完整笔记保留原貌，复习提纲帮助快速接上思路。', sources: ['K1'] },
  ],
  modes: [
    { role: 'USER', text: 'RayAgent 的三种模式，应该怎么选？' },
    { role: 'ASSISTANT', title: '先看这个问题需要从哪里找依据。', paragraphs: ['同一个对话入口，按你选择的模式执行不同的回答流程。'], points: ['智能对话：解释概念、讨论思路；需要新信息时可以联网。', '知识库问答：只从你选定的资料中寻找答案，缺少依据会明确说明。', '自动识别：判断是否需要资料、网页或两者组合，并区分来源。'], ending: '如果你正在核对一份文档里的内容，就新建知识库问答，再选择对应资料。', sources: [] },
  ],
  rag: [
    { role: 'USER', text: '用一条请求梳理 RAG 的完整链路。' },
    { role: 'ASSISTANT', title: '先找证据，再组织回答。', paragraphs: ['问题进入后端后，先限定当前用户、知识库、有效文档版本和向量空间，再检索相关片段。'], points: ['检索候选并组装证据，判断它们是否足以回答。', '依据足够时，生成带来源编号的回答。', '点击来源时，重新检查版本与归属，返回对应原文。'], ending: '资料不足时要直接说明，不能用看起来合理的文字填补证据空缺。', sources: ['K1'] },
  ],
}
const documents = [
  { name: 'RayAgent 设计笔记.md', kind: 'Markdown', detail: '三种模式、会话与上下文', date: '今天 10:24' },
  { name: 'Agent 循环拆解.md', kind: 'Markdown', detail: '模型调用、工具执行与结束条件', date: '昨天 20:16' },
]
const view = ref('chat')
const selected = ref(null)
const currentMode = ref('CHAT')
const currentScope = ref([])
const messages = ref([])
const draft = ref('')
const query = ref('')
const theme = ref(document.documentElement.dataset.theme || 'light')
const collapsed = ref(false)
const mobileOpen = ref(false)
const menuOpen = ref(false)
const wide = ref(matchMedia('(min-width: 901px)').matches)
const input = ref(null)
const scroller = ref(null)
const sidebar = ref(null)
const createDialog = ref(null)
const sourceDialog = ref(null)
const adminDialog = ref(null)
const modeButton = ref(null)
const createMode = ref('CHAT')
const createScope = ref(true)
const createName = ref('')
const activeSource = ref('K1')
const activeParagraph = ref(3)
const activePassage = ref('原始历史与摘要投影分开保存，摘要应用后不删除原始消息。后续请求将摘要和近期消息组装为模型输入；摘要是历史信息，不能作为当前资料或网页的事实依据。')
const notice = ref('')
const follow = ref(true)
let noticeTimer, returnFocus
const media = matchMedia('(min-width: 901px)')
const title = computed(() => sessions.value.find(s => s.id === selected.value)?.title || '新的对话')
const visibleSessions = computed(() => sessions.value.filter(s => s.title.toLowerCase().includes(query.value.toLowerCase())))
const sidebarHidden = computed(() => wide.value ? collapsed.value : !mobileOpen.value)
const pageTitle = computed(() => ({ chat: title.value, knowledge: '知识库', tasks: '任务与运行' })[view.value])

function applyTheme() {
  theme.value = theme.value === 'light' ? 'dark' : 'light'
  document.documentElement.dataset.theme = theme.value
  try { localStorage.setItem('ray-design-theme', theme.value) } catch { toast('本页外观已切换，浏览器未允许保存偏好。') }
}
function toast(text) { notice.value = text; clearTimeout(noticeTimer); noticeTimer = setTimeout(() => { notice.value = '' }, 4500) }
function closeMenu() { menuOpen.value = false }
async function toggleMenu() { menuOpen.value = !menuOpen.value; if (menuOpen.value) { await nextTick(); document.querySelector('.mode-menu button')?.focus() } }
function menuKey(event) {
  if (event.key === 'Escape') { closeMenu(); modeButton.value.focus(); return }
  if (!['ArrowUp', 'ArrowDown'].includes(event.key)) return
  event.preventDefault()
  const items = [...document.querySelectorAll('.mode-menu button')]
  const index = items.indexOf(document.activeElement)
  items[(index + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length]?.focus()
}
async function toggleSidebar() {
  if (wide.value) { collapsed.value = !collapsed.value; return }
  if (mobileOpen.value) return closeSidebar()
  returnFocus = document.activeElement; mobileOpen.value = true
  await nextTick(); sidebar.value.querySelector('.mobile-close').focus()
}
async function closeSidebar() { mobileOpen.value = false; await nextTick(); returnFocus?.focus() }
function sidebarKey(event) {
  if (wide.value || !mobileOpen.value) return
  if (event.key === 'Escape') { closeSidebar(); return }
  if (event.key !== 'Tab') return
  const items = [...sidebar.value.querySelectorAll('button,input')].filter(el => !el.disabled && el.getClientRects().length)
  if (event.shiftKey && document.activeElement === items[0]) { event.preventDefault(); items.at(-1).focus() }
  else if (!event.shiftKey && document.activeElement === items.at(-1)) { event.preventDefault(); items[0].focus() }
}
function navigate(next) { view.value = next; closeMenu(); if (!wide.value) closeSidebar() }
async function openSession(session) {
  navigate('chat'); selected.value = session.id; currentMode.value = session.mode; currentScope.value = [...session.scope]
  messages.value = [...(demoMessages[session.id] || [])]; draft.value = ''; follow.value = true
  await nextTick(); bottom()
}
async function newChat(mode = currentMode.value) {
  closeMenu(); createMode.value = mode; createName.value = ''; createScope.value = true
  if (!wide.value) await closeSidebar()
  createDialog.value.showModal()
}
async function createChat() {
  if (createMode.value === 'KNOWLEDGE' && !createScope.value) return
  currentMode.value = createMode.value
  currentScope.value = createMode.value === 'CHAT' || !createScope.value ? [] : ['RayAgent 设计笔记']
  selected.value = null; messages.value = []; draft.value = ''; view.value = 'chat'; follow.value = true
  createDialog.value.close(); await nextTick(); input.value.focus()
}
function grow() { input.value.style.height = 'auto'; input.value.style.height = Math.min(input.value.scrollHeight, 156) + 'px' }
function scrollChanged() { const el = scroller.value; follow.value = el.scrollHeight - el.scrollTop - el.clientHeight < 100 }
function bottom() { if (scroller.value && follow.value) scroller.value.scrollTop = scroller.value.scrollHeight }
async function suggestion(text) { draft.value = text; await nextTick(); grow(); input.value.focus() }
async function send() {
  const text = draft.value.trim(); if (!text) return
  // 模拟对话只展示自写资料；交付说明明确未调用模型，绝不请求真实后端。
  if (!selected.value) {
    const id = 'local-' + Date.now(); selected.value = id
    sessions.value.unshift({ id, title: createName.value.trim() || text.slice(0, 22), mode: currentMode.value, scope: [...currentScope.value], group: '今天' })
  }
  const answer = /摘要|历史|上下文/.test(text) ? demoMessages.notes[1] : /RAG|检索|资料/.test(text) ? demoMessages.rag[1] : demoMessages.modes[1]
  messages.value.push({ role: 'USER', text }, { ...answer, sources: currentMode.value === 'CHAT' ? [] : answer.sources })
  demoMessages[selected.value] = [...messages.value]; draft.value = ''; follow.value = true
  await nextTick(); grow(); bottom()
}
function onKey(event) { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) { event.preventDefault(); send() } }
function source(id = 'K1', message) {
  activeSource.value = id
  const retrieval = message?.title?.includes('先找证据')
  activeParagraph.value = retrieval ? 5 : 3
  activePassage.value = retrieval
    ? '检索前限定当前用户、知识库、有效文档版本与向量空间。候选片段形成回答证据，依据不足时明确说明。引用读取时重新校验归属及有效版本。'
    : '原始历史与摘要投影分开保存，摘要应用后不删除原始消息。后续请求将摘要和近期消息组装为模型输入；摘要是历史信息，不能作为当前资料或网页的事实依据。'
  sourceDialog.value.showModal()
}
async function copyMessage(message) {
  try { await navigator.clipboard.writeText([message.title, ...message.paragraphs, ...message.points, message.ending].join('\n\n')); toast('回答已复制') }
  catch { toast('复制失败，请选择正文后复制。') }
}
function resized(event) { wide.value = event.matches; if (event.matches) mobileOpen.value = false }
onMounted(() => media.addEventListener('change', resized))
onUnmounted(() => { media.removeEventListener('change', resized); clearTimeout(noticeTimer) })
</script>

<template>
  <main class="design-shell" :class="{ 'is-collapsed': wide && collapsed }">
    <button v-if="!wide && mobileOpen" class="nav-backdrop" aria-label="关闭导航" @click="closeSidebar"/>
    <aside ref="sidebar" class="sidebar" :class="{ 'is-open': mobileOpen }" :inert="sidebarHidden" :aria-hidden="sidebarHidden" @keydown="sidebarKey">
      <div class="brand"><img :src="brand" alt=""/><div><strong>keeprunning</strong><span>RayAgent <b>V2</b></span></div><button class="icon-button mobile-close" aria-label="关闭侧栏" @click="closeSidebar"><UiIcon name="close"/></button></div>
      <button class="new-button" @click="newChat()"><UiIcon name="plus" :size="19"/><span>新对话</span></button>
      <nav class="navigation" aria-label="工作区导航"><button :aria-current="view === 'chat' ? 'page' : undefined" @click="navigate('chat')"><UiIcon name="chat" :size="19"/>对话</button><button :aria-current="view === 'knowledge' ? 'page' : undefined" @click="navigate('knowledge')"><UiIcon name="book" :size="19"/>知识库</button><button :aria-current="view === 'tasks' ? 'page' : undefined" @click="navigate('tasks')"><UiIcon name="activity" :size="19"/>任务与运行</button></nav>
      <div class="session-search"><svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><circle cx="10" cy="10" r="6"/><path d="m15 15 5 5"/></svg><label class="sr-only" for="search">搜索对话</label><input id="search" v-model="query" placeholder="搜索对话"/></div>
      <div class="session-list"><template v-for="group in ['今天', '昨天']" :key="group"><h2 v-if="visibleSessions.some(s => s.group === group)">{{ group }}</h2><button v-for="session in visibleSessions.filter(s => s.group === group)" :key="session.id" :aria-current="selected === session.id && view === 'chat' ? 'true' : undefined" :title="session.title" class="session" @click="openSession(session)"><UiIcon :name="modeInfo[session.mode].icon" :size="16"/><span>{{ session.title }}</span><i v-if="session.mode === 'KNOWLEDGE'" class="session-dot" aria-label="知识库问答"/></button></template><p v-if="!visibleSessions.length" class="no-results">没有找到相关对话</p></div>
      <footer class="sidebar-footer"><button class="admin-link" @click="adminDialog.showModal()"><UiIcon name="settings" :size="18"/>管理后台<UiIcon name="chevron" :size="14"/></button><div class="account"><span class="avatar">K</span><div><strong>KeepRising</strong><span>个人工作空间</span></div><span class="account-indicator" title="个人账号"/></div></footer>
    </aside>

    <section class="workspace" :inert="!wide && mobileOpen">
      <header class="topbar"><div class="topbar-left"><button class="icon-button" :aria-label="sidebarHidden ? '打开导航' : '收起导航'" :aria-expanded="!sidebarHidden" @click="toggleSidebar"><UiIcon name="menu" :size="20"/></button><span class="page-name">{{ pageTitle }}</span></div><div class="topbar-right"><button class="theme-button" :aria-label="theme === 'light' ? '切换深色' : '切换浅色'" @click="applyTheme"><UiIcon :name="theme === 'light' ? 'moon' : 'sun'" :size="17"/><span>{{ theme === 'light' ? '深色' : '浅色' }}</span></button><div v-if="view === 'chat'" class="mode-control" @focusout="event => { if (!event.currentTarget.contains(event.relatedTarget)) closeMenu() }"><button ref="modeButton" class="mode-button" aria-haspopup="menu" :aria-expanded="menuOpen" @click="toggleMenu"><UiIcon :name="modeInfo[currentMode].icon" :size="17"/><span>{{ modeInfo[currentMode].name }}</span><UiIcon name="chevron" :size="14" class="down"/></button><div v-if="menuOpen" class="mode-menu" role="menu" aria-label="选择新对话模式" @keydown="menuKey"><p>在新对话中使用</p><button v-for="(mode, id) in modeInfo" :key="id" role="menuitemradio" :aria-checked="currentMode === id" @click="newChat(id)"><span class="mode-icon"><UiIcon :name="mode.icon"/></span><span><strong>{{ mode.name }}</strong><small>{{ mode.detail }}</small></span><UiIcon v-if="currentMode === id" name="check" :size="17"/></button><div class="menu-note">切换模式会开始新对话，已有会话保持原样。</div></div></div></div></header>

      <template v-if="view === 'chat'">
        <div v-if="currentScope.length" class="scope-bar"><UiIcon name="book" :size="15"/><span>资料范围</span><button @click="source()">{{ currentScope.join('、') }}<UiIcon name="chevron" :size="13"/></button><span class="scope-boundary">{{ currentMode === 'KNOWLEDGE' ? '只根据资料回答' : '资料与网页分别标明来源' }}</span></div>
        <div ref="scroller" class="message-area" tabindex="0" role="region" aria-label="对话内容" @scroll="scrollChanged">
          <div v-if="!messages.length" class="welcome reading-width"><div class="assistant-identity"><span class="assistant-orbit"><UiIcon name="settings" :size="16"/></span><span>RayAgent</span><span class="identity-dot"/></div><h1>你好，<span>KeepRising</span></h1><div class="suggestions"><button @click="suggestion('用一个具体例子解释 Agent 的工具调用')"><UiIcon name="chat" :size="17"/>读懂一个概念<UiIcon name="chevron" :size="13"/></button><button @click="suggestion('梳理 RAG 从检索到引用的完整链路')"><UiIcon name="book" :size="17"/>梳理一条链路<UiIcon name="chevron" :size="13"/></button></div></div>
          <div v-else class="message-list reading-width"><article v-for="(message, index) in messages" :key="index" class="message" :class="message.role === 'USER' ? 'user-message' : 'assistant-message'"><div v-if="message.role === 'USER'" class="user-bubble">{{ message.text }}</div><template v-else><div class="answer-brand"><span class="assistant-orbit"><UiIcon name="settings" :size="17"/></span><strong>RayAgent</strong></div><div class="answer-body"><h2>{{ message.title }}</h2><p v-for="paragraph in message.paragraphs" :key="paragraph">{{ paragraph }}</p><ul><li v-for="point in message.points" :key="point">{{ point }}</li></ul><p>{{ message.ending }}</p><div v-if="message.sources.length" class="answer-sources"><button v-for="id in message.sources" :key="id" :aria-label="`查看此回答的 ${id} 来源`" @click="source(id, message)"><UiIcon name="book" :size="15"/><span>{{ id }} · RayAgent 设计笔记</span><UiIcon name="chevron" :size="12"/></button></div><div class="answer-actions"><button aria-label="复制回答" @click="copyMessage(message)"><UiIcon name="copy" :size="16"/></button><span>{{ modeInfo[currentMode].name }}</span></div></div></template></article></div>
        </div>
        <div class="composer-dock reading-width"><button v-if="!follow && messages.length" class="to-bottom icon-button" aria-label="回到最新消息" @click="follow = true; bottom()"><UiIcon name="arrow"/></button><form class="composer" @submit.prevent="send"><label for="message" class="sr-only">你的问题</label><textarea id="message" ref="input" v-model="draft" rows="1" maxlength="4000" :placeholder="currentMode === 'KNOWLEDGE' ? '向你的资料提问…' : '输入你的问题…'" @input="grow" @keydown="onKey"/><div class="composer-toolbar"><div class="model-picker"><UiIcon name="settings" :size="14"/><label for="model" class="sr-only">选择模型</label><select id="model"><option>GLM-5.3-Flash</option><option>默认兼容模型</option></select></div><span class="keyboard-hint">Enter 发送 <span>·</span> Shift + Enter 换行</span><button class="send-button" :disabled="!draft.trim()" aria-label="发送" type="submit"><UiIcon name="arrow" :size="20"/></button></div></form><p class="composer-disclaimer">RayAgent 的回答可能有误，重要信息请核对来源。</p></div>
      </template>

      <section v-else-if="view === 'knowledge'" class="library reading-width"><div class="section-heading"><span class="section-icon"><UiIcon name="book" :size="24"/></span><div><h1>你的知识库</h1><p>资料放在这里，答案回到原文。</p></div></div><div class="library-heading"><h2>RayAgent 设计笔记</h2><span>2 份文档</span></div><button v-for="doc in documents" :key="doc.name" class="document-row" @click="source()"><span class="document-icon"><UiIcon name="book" :size="22"/></span><span class="document-name"><strong>{{ doc.name }}</strong><small>{{ doc.detail }}</small></span><span class="document-state"><i/>可检索</span><UiIcon name="chevron" :size="16"/></button><button class="text-button" @click="newChat('KNOWLEDGE')">使用这些资料开始问答<UiIcon name="chevron" :size="15"/></button></section>
      <section v-else class="tasks-empty reading-width"><span class="section-icon"><UiIcon name="activity" :size="26"/></span><h1>当前没有运行中的任务</h1><p>新的对话与文档处理，会在这里展示运行进度。</p><button class="secondary-button" @click="navigate('chat')">回到对话<UiIcon name="chevron" :size="15"/></button></section>
    </section>

    <dialog ref="createDialog" class="create-dialog" aria-labelledby="new-title"><form @submit.prevent="createChat"><div class="dialog-top"><span class="section-icon"><UiIcon :name="modeInfo[createMode].icon" :size="22"/></span><button type="button" class="icon-button" aria-label="关闭新对话" @click="createDialog.close()"><UiIcon name="close"/></button></div><h2 id="new-title">开始一段{{ modeInfo[createMode].name }}</h2><p class="dialog-intro">{{ modeInfo[createMode].detail }}</p><label for="new-name">对话名称<span>可选</span></label><input id="new-name" v-model="createName" placeholder="给这次对话起个名字" maxlength="80"/><label for="new-mode">回答模式</label><select id="new-mode" v-model="createMode"><option v-for="(mode, id) in modeInfo" :key="id" :value="id">{{ mode.name }}</option></select><div v-if="createMode !== 'CHAT'" class="scope-picker"><label><input v-model="createScope" type="checkbox"/>RayAgent 设计笔记<small>2 份文档</small></label><p v-if="createMode === 'KNOWLEDGE'">至少选择一个知识库。资料不足时会明确说明。</p><p v-else>不选择资料时，自动识别仅使用对话与联网。</p></div><p class="session-rule">模式与资料范围只用于这段新对话。</p><button class="primary-button" :disabled="createMode === 'KNOWLEDGE' && !createScope">开始对话<UiIcon name="arrow" :size="17"/></button></form></dialog>

    <dialog ref="sourceDialog" class="source-dialog" aria-labelledby="source-title"><div class="source-heading"><div><span class="source-eyebrow">知识库来源 · {{ activeSource }}</span><h2 id="source-title">回到答案的依据</h2></div><button class="icon-button" aria-label="关闭来源" @click="sourceDialog.close()"><UiIcon name="close"/></button></div><div class="source-body"><div class="source-document"><span class="document-icon"><UiIcon name="book" :size="24"/></span><div><h3>RayAgent 设计笔记.md</h3><p>版本 1 <span>·</span> 第 {{ activeParagraph }} 段</p></div></div><div class="source-section-label"><span>引用原文</span><span class="source-valid"><UiIcon name="check" :size="14"/>当前有效</span></div><blockquote>{{ activePassage }}</blockquote><p class="source-context">所属知识库<span>RayAgent 设计笔记</span></p><div class="source-tip"><UiIcon name="book" :size="17"/><p>引用与这条回答关联。资料更新或删除后，需要重新核对有效版本。</p></div><button class="secondary-button" @click="sourceDialog.close()">回到对话<UiIcon name="chevron" :size="15"/></button></div></dialog>
    <dialog ref="adminDialog" class="admin-dialog" aria-labelledby="admin-title"><div class="dialog-top"><span class="section-icon"><UiIcon name="settings" :size="22"/></span><button class="icon-button" aria-label="关闭管理后台" @click="adminDialog.close()"><UiIcon name="close"/></button></div><h2 id="admin-title">管理后台</h2><p class="dialog-intro">统一管理个人工作空间的运行配置。</p><dl><div><dt>默认模型</dt><dd>GLM-5.3-Flash</dd></div><div><dt>向量模型</dt><dd>BAAI/bge-m3 · 1024 维</dd></div><div><dt>资料与权限</dt><dd>个人知识库</dd></div></dl><button class="secondary-button" @click="adminDialog.close()">返回工作空间</button></dialog>
    <div v-if="notice" class="toast" role="status"><UiIcon name="check" :size="17"/>{{ notice }}</div>
  </main>
</template>
