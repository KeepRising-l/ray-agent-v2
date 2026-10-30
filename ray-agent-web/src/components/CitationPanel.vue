<script setup>
import { computed, ref, watch } from 'vue'
import { citationsFromEvents } from '../api/citations.js'
const props = defineProps({ run: Object, events: { type: Array, default: () => [] } })
const trace = computed(() => citationsFromEvents(props.events))
const opened = ref({})
watch(() => props.run?.id, () => { opened.value = {} })

async function open(source) {
  const runId = props.run.id
  try {
    const response = await fetch(`/api/runs/${runId}/citations/${source.id}`)
    if (!response.ok) throw new Error()
    const detail = await response.json()
    if (runId === props.run?.id) opened.value = { ...opened.value, [source.id]: detail }
  } catch {
    if (runId === props.run?.id) opened.value = { ...opened.value, [source.id]: { error: '无法读取引用，请检查后端或运行权限' } }
  }
}
</script>

<template>
  <section v-if="trace.citations.length || trace.refused" aria-label="知识库引用">
    <h2>知识库资料</h2>
    <p v-if="trace.refused">本次资料依据不足，没有生成资料事实回答。</p>
    <article v-for="source in trace.citations" :key="source.id" class="citation-card">
      <button type="button" @click="open(source)">[{{ source.id }}] {{ source.documentName }}</button>
      <span> · v{{ source.version }} · {{ source.page ? `第${source.page}页` : '文本' }} · 第{{ source.paragraph }}段</span>
      <p>{{ trace.used.includes(source.id) && run?.status === 'SUCCEEDED' ? '回答已引用' : '检索候选' }} · 相似度 {{ source.score.toFixed(3) }}</p>
      <template v-if="opened[source.id]">
        <p v-if="opened[source.id].error">{{ opened[source.id].error }}</p>
        <p v-else-if="!opened[source.id].valid" class="expired">引用已失效：资料已更新或删除。</p>
        <pre v-else>{{ opened[source.id].text }}</pre>
      </template>
    </article>
  </section>
</template>

<style scoped>
h2 { font-size: 1.1rem; }
.citation-card { border: 1px solid #ddd; padding: .8rem; margin: .6rem 0; overflow-wrap: anywhere; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
.expired { color: #a33; }
</style>
