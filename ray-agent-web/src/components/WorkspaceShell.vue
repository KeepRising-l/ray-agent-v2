<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { createChatSession } from '../stores/chatSession.js'
import ChatWorkspace from '../pages/ChatWorkspace.vue'
import KnowledgeWorkspace from '../pages/KnowledgeWorkspace.vue'
import AdminLayout from '../pages/admin/AdminLayout.vue'
import TaskPanel from './TaskPanel.vue'
import SessionList from './SessionList.vue'
import ChatModeSelector from './ChatModeSelector.vue'
import KnowledgeSelector from './KnowledgeSelector.vue'
import UiIcon from './UiIcon.vue'
import BrandMark from './BrandMark.vue'
defineProps({ user: String, logout: Function })

// 会话状态仍由原store管理；Shell只集中页面布局、导航与原有初始化/销毁生命周期。
const chat = createChatSession()
const s = chat.state
const view = ref('chat')
const pages = { chat: ['聊天', 'chat'], knowledge: ['知识库管理', 'book'], tasks: ['任务与运行', 'activity'], admin: ['管理后台', 'settings'] }
// 与CSS断点一致：桌面常驻侧栏，窄屏需要显式打开，并隔离背景焦点。
const wide = ref(matchMedia('(min-width: 981px)').matches)
const sidebarOpen = ref(false)
const sidebar = ref(null)
const createDialog = ref(null)
const creationError = ref('')
const theme = ref('light')
let timer, disposed = false, returnFocus
const media = matchMedia('(min-width: 981px)')
const activeTitle = computed(() => pages[view.value][0])
function applyTheme(value) {
  theme.value = value
  document.documentElement.dataset.theme = value
  try { localStorage.setItem('ray-ui-theme', value) } catch { /* 禁用本机存储时仍可在本页切换。 */ }
}
function toggleTheme() { applyTheme(theme.value === 'light' ? 'dark' : 'light') }
async function closeSidebar() {
  sidebarOpen.value = false
  await nextTick()
  if (returnFocus?.isConnected && returnFocus.getClientRects().length) returnFocus.focus()
}
async function openSidebar() {
  returnFocus = document.activeElement
  sidebarOpen.value = true
  await nextTick()
  sidebar.value?.querySelector('.sidebar-close')?.focus()
}
function trapSidebar(event) {
  if (wide.value || !sidebarOpen.value || event.key !== 'Tab') return
  const items = [...sidebar.value.querySelectorAll('button:not(:disabled), a[href]')].filter(el => el.getClientRects().length)
  const edge = event.shiftKey ? items[0] : items.at(-1)
  if (document.activeElement === edge) { event.preventDefault(); (event.shiftKey ? items.at(-1) : items[0])?.focus() }
}
async function navigate(id) { view.value = id; if (!wide.value) await closeSidebar(); if (id === 'chat') await chat.probe() }
async function selectSession(id) { view.value = 'chat'; if (!wide.value) await closeSidebar(); await chat.select(id) }
async function newSession() { creationError.value = ''; if (!wide.value) await closeSidebar(); createDialog.value.showModal() }
// 顶部模式菜单只预选新会话模式；当前会话的模式与资料范围保持不变。
function startNewSession(mode) { s.newMode = mode; creationError.value = ''; if (!wide.value) closeSidebar(); createDialog.value.showModal() }
async function create() {
  // 创建失败保留表单及原会话；只有服务端确认成功后才关闭窗口。
  const created = await chat.create()
  if (!created) { creationError.value = s.error?.message || '尚未创建成功，请稍后重试'; return }
  view.value = 'chat'
  createDialog.value.close()
  await nextTick()
  document.querySelector('#message')?.focus()
}
const resized = event => { wide.value = event.matches; if (event.matches) sidebarOpen.value = false }
onMounted(async () => {
  try { applyTheme(localStorage.getItem('ray-ui-theme') === 'dark' ? 'dark' : 'light') } catch { applyTheme('light') }
  media.addEventListener('change', resized)
  await chat.init()
  if (!disposed) timer = setInterval(chat.poll, 1000)
})
onUnmounted(() => { disposed = true; clearInterval(timer); media.removeEventListener('change', resized); chat.dispose() })
</script>

<template>
  <main class="app-shell">
    <button v-if="sidebarOpen && !wide" class="sidebar-backdrop" type="button" aria-label="关闭导航" @click="closeSidebar"/>
    <aside ref="sidebar" class="app-sidebar" :class="{ 'is-open': sidebarOpen }" :inert="!wide && !sidebarOpen" :aria-hidden="!wide && !sidebarOpen" @keydown="trapSidebar" @keydown.esc="closeSidebar">
      <div class="sidebar-brand"><BrandMark/><span>RayAgent</span><em>V2</em><button class="icon-button sidebar-close" aria-label="关闭侧栏" @click="closeSidebar"><UiIcon name="close"/></button></div>
      <button class="new-chat-button" @click="newSession"><UiIcon name="plus"/><span>新建对话</span></button>
      <nav class="primary-nav" aria-label="功能切换"><button v-for="(page, id) in pages" :key="id" :aria-current="view === id ? 'page' : undefined" @click="navigate(id)"><UiIcon :name="page[1]"/><span>{{ page[0] }}</span></button></nav>
      <SessionList :sessions="s.sessions" :selected="s.sessionId" :disabled="s.creating" @select="selectSession"/>
      <div class="sidebar-account">
        <div class="account-row"><span class="account-avatar">{{ user?.slice(0, 1).toUpperCase() }}</span><div class="account-name"><strong>{{ user }}</strong><span>个人工作空间</span></div><button class="icon-button" :aria-label="`退出登录 · ${user}`" title="退出登录" @click="logout"><UiIcon name="logout" :size="18"/></button></div>
      </div>
    </aside>
    <div class="workspace-main" :inert="sidebarOpen && !wide">
      <ChatWorkspace v-show="view === 'chat'" :chat="chat" :user="user" :theme="theme" @open-nav="openSidebar" @toggle-theme="toggleTheme" @pick-mode="startNewSession"/>
      <template v-if="view !== 'chat'">
        <header class="page-toolbar"><button class="icon-button mobile-menu" aria-label="打开导航" @click="openSidebar"><UiIcon name="menu"/></button><span>{{ activeTitle }}</span><span class="toolbar-context">RayAgent / 个人工作空间</span><button class="theme-button" :aria-label="theme === 'light' ? '切换深色模式' : '切换浅色模式'" @click="toggleTheme"><UiIcon :name="theme === 'light' ? 'moon' : 'sun'" :size="17"/><span>{{ theme === 'light' ? '深色' : '浅色' }}</span></button></header>
        <div class="secondary-workspace"><KnowledgeWorkspace v-if="view === 'knowledge'"/><TaskPanel v-if="view === 'tasks'" @open-session="selectSession"/><AdminLayout v-if="view === 'admin'" @open-session="selectSession"/></div>
      </template>
    </div>
    <dialog ref="createDialog" class="session-dialog" aria-labelledby="create-title">
      <div class="dialog-heading"><div><p class="eyebrow">开始新的话题</p><h2 id="create-title">新建会话</h2></div><button class="icon-button" aria-label="关闭新建会话" @click="createDialog.close()"><UiIcon name="close"/></button></div>
      <form @submit.prevent="create">
        <label for="session-title">名称（可选）</label><input id="session-title" v-model="s.title" maxlength="80" placeholder="给这次对话起个名字" :disabled="s.creating"/>
        <ChatModeSelector v-model="s.newMode" :disabled="s.creating"/>
        <KnowledgeSelector v-if="s.newMode !== 'CHAT'" v-model="s.newBases" :bases="s.bases" :disabled="s.creating"/>
        <p class="form-help">只设置新会话，当前对话的模式与资料范围不会改变。</p>
        <p v-if="creationError" role="alert">{{ creationError }}</p>
        <div class="dialog-actions"><button type="button" @click="createDialog.close()">取消</button><button class="primary" :disabled="s.creating || (s.newMode === 'KNOWLEDGE' && !s.newBases.length)">{{ s.creating ? '正在创建…' : '新建会话' }}</button></div>
      </form>
    </dialog>
  </main>
</template>
