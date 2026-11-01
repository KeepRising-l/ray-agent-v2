<script setup>defineProps({ source: Object, detail: Object })</script>
<template>
  <div v-if="detail" class="source-preview">
    <p v-if="detail.loading" role="status">正在核对原文…</p>
    <p v-else-if="detail.error">{{ detail.error }}</p>
    <p v-else-if="!detail.valid" class="expired">引用已失效：资料已更新或删除。</p>
    <template v-else>
      <pre>{{ detail.text }}</pre>
    </template>
  </div>
</template>
<style scoped>pre { white-space: pre-wrap; overflow-wrap: anywhere; background: var(--surface); padding: .8rem; }.expired { color: var(--danger); }</style>
