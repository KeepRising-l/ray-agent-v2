<script setup>
import { nextTick, ref } from 'vue'
import ChatWorkspace from './pages/ChatWorkspace.vue'
import KnowledgeWorkspace from './pages/KnowledgeWorkspace.vue'
import TaskPanel from './components/TaskPanel.vue'
import AuthGate from './components/AuthGate.vue'
import AdminLayout from './pages/admin/AdminLayout.vue'
const view = ref('chat')
const chat = ref(null)
async function openSession(id) {
  view.value = 'chat'
  await nextTick()
  await chat.value.openSession(id)
}
async function openChat() {
  view.value = 'chat'
  await chat.value?.refresh()
}
</script>
<template>
  <AuthGate v-slot="{ user, logout }"><main>
    <header class="app-header"><h1>RayAgent V2</h1>
      <nav aria-label="功能切换">
        <button :aria-current="view === 'chat' ? 'page' : undefined" @click="openChat">聊天</button>
        <button :aria-current="view === 'knowledge' ? 'page' : undefined" @click="view = 'knowledge'">知识库管理</button>
        <button :aria-current="view === 'tasks' ? 'page' : undefined" @click="view = 'tasks'">任务与运行</button>
        <button :aria-current="view === 'admin' ? 'page' : undefined" @click="view = 'admin'">管理后台</button>
        <button @click="logout">退出登录 · {{ user }}</button>
      </nav>
    </header>
    <KnowledgeWorkspace v-if="view === 'knowledge'" />
    <TaskPanel v-if="view === 'tasks'" @open-session="openSession" />
    <AdminLayout v-if="view === 'admin'" @open-session="openSession" />
    <ChatWorkspace v-show="view === 'chat'" ref="chat" />
  </main></AuthGate>
</template>
<style>
:root { font-family: system-ui, 'Microsoft YaHei', sans-serif; color: #25292f; line-height: 1.65; background: #fff; }
body { margin: 0; }main { max-width: 1160px; margin: 1.5rem auto; padding: 0 1.25rem 3rem; }
h1 { font-size: 1.6rem; margin: 0; }h2 { font-size: 1.22rem; margin: 0 0 .8rem; }h3 { font-size: 1.08rem; }
.app-header { display: flex; gap: 1.5rem; align-items: center; justify-content: space-between; border-bottom: 1px solid #ddd; padding-bottom: 1rem; margin-bottom: 1.5rem; flex-wrap: wrap; }
label { display: block; margin: .6rem 0 .25rem; }
input, select, textarea, button { font: inherit; box-sizing: border-box; max-width: 100%; }
select, input:not([type='checkbox']), textarea { border: 1px solid #afb4ba; border-radius: 4px; padding: .4rem; }
select, textarea { width: 100%; }textarea { resize: vertical; }
button { cursor: pointer; border: 1px solid #afb4ba; border-radius: 4px; padding: .4rem .8rem; margin: .35rem .4rem .35rem 0; background: #fafafa; transition: background .15s, border-color .15s, transform .08s; }
button:active:not(:disabled) { transform: translateY(1px); }
button:hover:not(:disabled), button[aria-current='page'] { background: #e9edf1; border-color: #555d68; }
button.primary { background: #294e71; color: #fff; border-color: #294e71; }
button:disabled { opacity: .55; cursor: default; }:focus-visible { outline: 2px solid #294e71; outline-offset: 3px; }
summary { cursor: pointer; }details { margin: .9rem 0; }details[open] > summary { margin-bottom: .5rem; }
p, li, dd { overflow-wrap: anywhere; }
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { animation: none !important; transition: none !important; } }
</style>
