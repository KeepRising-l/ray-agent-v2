<script setup>
import { documentStates } from '../api/knowledge.js'
defineProps({ documents: Array, busy: Boolean, selected: String, currentProfile: String })
defineEmits(['select'])
</script>
<template>
  <ul aria-label="文档列表" class="document-status-list">
    <li v-for="doc in documents" :key="doc.id" :data-status="doc.status">
      <button :disabled="busy" :aria-pressed="doc.id === selected" @click="$emit('select', doc)">{{ doc.name }}</button>
      <span> · v{{ doc.latestVersion }} · {{ documentStates[doc.status] }} · {{ doc.chunkCount }} 个片段</span>
      <p v-if="currentProfile && doc.activeVersionId && doc.indexProfile !== currentProfile">当前向量空间已变化，需要重建索引后用于问答。</p>
      <p v-else-if="doc.status === 'READY'">此版本已可用于知识库问答。</p>
      <p v-else-if="doc.activeVersionId">新版本尚未就绪，问答仍使用上一有效版本。</p>
      <p v-else>此文档尚无可用于问答的有效版本。</p>
      <p v-if="doc.errorMessage">{{ doc.errorMessage }}</p>
    </li>
    <li v-if="!documents.length">还没有文档，上传后可在这里查看处理状态。</li>
  </ul>
</template>
<style scoped>.document-status-list { padding: 0; list-style: none; }li { border-bottom: 1px solid #ddd; padding: .8rem 0; }p { margin: .3rem 0; font-size: .94rem; }</style>
