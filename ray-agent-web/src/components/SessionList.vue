<script setup>
defineProps({ sessions: Array, selected: String, disabled: Boolean })
defineEmits(['select'])
</script>
<template>
  <section aria-label="会话列表">
    <h2>会话</h2><label for="session">切换会话</label>
    <select id="session" :value="selected" :disabled="disabled" @change="$emit('select', $event.target.value)">
      <option v-if="!sessions.length" value="">还没有会话</option>
      <option v-for="session in sessions" :key="session.id" :value="session.id">{{ session.title }} · {{ session.mode }} · {{ session.id.slice(0, 8) }}</option>
    </select>
  </section>
</template>
