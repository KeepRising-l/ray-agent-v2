<script setup>
defineProps({ mode: { type: String, default: 'CHAT' } })
const labels = { CHAT: '可联网 · 不读知识库', KNOWLEDGE: '仅所选资料', AUTO: '资料与网页分开标注' }
</script>
<template><p class="mode-badge"><span class="sr-only">当前会话模式：</span><strong>{{ mode }}</strong><span class="mode-separator"> · </span>{{ labels[mode] }}</p></template>
