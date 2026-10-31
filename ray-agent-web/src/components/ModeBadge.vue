<script setup>
defineProps({ mode: { type: String, default: 'CHAT' } })
const labels = { CHAT: '智能对话 · 可联网，不读知识库', KNOWLEDGE: '知识库问答 · 仅所选资料', AUTO: '自动路由 · 资料与网页分别标来源' }
</script>
<template><p class="mode-badge">当前会话模式：<strong>{{ mode }}</strong> · {{ labels[mode] }}</p></template>
<style scoped>.mode-badge { margin: .4rem 0; font-size: .94rem; }</style>
