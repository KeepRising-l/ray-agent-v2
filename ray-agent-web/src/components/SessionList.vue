<script setup>
defineProps({ sessions: Array, selected: String, disabled: Boolean })
defineEmits(['select'])
</script>
<template>
  <section class="session-list" aria-label="会话列表"><h2>你的对话</h2>
    <p v-if="!sessions?.length" class="empty-sessions">新的想法，从一次对话开始。</p>
    <div class="session-items"><button v-for="session in sessions" :key="session.id" class="session-item" :data-session-id="session.id" :aria-current="selected === session.id ? 'true' : undefined" :title="session.title + ' · ' + session.mode" :disabled="disabled" @click="$emit('select', session.id)"><span>{{ session.title }}</span><small>{{ session.mode === 'KNOWLEDGE' ? '资料' : session.mode === 'AUTO' ? '自动' : '' }}</small></button></div>
  </section>
</template>
