<script setup>defineProps({ source: Object, detail: Object })</script>
<template>
  <div v-if="detail" class="source-preview">
    <p v-if="detail.loading" role="status">正在核对原文…</p>
    <p v-else-if="detail.error">{{ detail.error }}</p>
    <p v-else-if="!detail.valid" class="expired">引用已失效：资料已更新或删除。</p>
    <template v-else>
      <p>{{ source.documentName }} · v{{ source.version }} · {{ source.page ? `第${source.page}页` : '文本' }} · 第{{ source.paragraph }}段</p>
      <pre>{{ detail.text }}</pre>
    </template>
  </div>
</template>
<style scoped>pre { white-space: pre-wrap; overflow-wrap: anywhere; background: #f6f7f8; padding: .8rem; }.expired { color: #903a2e; }</style>
