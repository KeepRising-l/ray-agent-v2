<script setup>
import { computed, ref, watch } from 'vue'
import { citationsFromEvents } from '../api/citations.js'
import SourcePreview from './SourcePreview.vue'
const props = defineProps({ run: Object, events: { type: Array, default: () => [] } })
const trace = computed(() => citationsFromEvents(props.events))
const opened = ref({})
let selection = 0
const requests = new Map()
watch(() => props.run?.id, () => { ++selection; requests.clear(); opened.value = {} })

async function open(source) {
  const runId = props.run.id
  const token = selection
  const request = (requests.get(source.id) || 0) + 1
  requests.set(source.id, request)
  opened.value = { ...opened.value, [source.id]: { loading: true } }
  try {
    const response = await fetch(`/api/runs/${runId}/citations/${source.id}`)
    if (!response.ok) throw new Error()
    const detail = await response.json()
    if (runId === props.run?.id && token === selection && requests.get(source.id) === request) opened.value = { ...opened.value, [source.id]: detail }
  } catch {
    if (runId === props.run?.id && token === selection && requests.get(source.id) === request) opened.value = { ...opened.value, [source.id]: { error: '无法读取引用，请检查后端或运行权限' } }
  }
}
defineExpose({ openCitation: async id => {
  const source = trace.value.citations.find(item => item.id === id)
  if (!source) return false
  await open(source)
  return true
} })
</script>

<template>
  <section v-if="trace.citations.length || trace.refused" aria-label="知识库引用">
    <h2>知识库资料</h2>
    <p v-if="trace.refused">本次资料依据不足，没有生成资料事实回答。</p>
    <article v-for="source in trace.citations" :key="source.id" class="citation-card">
      <button type="button" :aria-label="`核对引用原文：${source.documentName}`" :aria-expanded="!!opened[source.id]" :aria-busy="!!opened[source.id]?.loading" @click="open(source)">[{{ source.id }}] {{ source.documentName }} · 核对原文</button>
      <span> · v{{ source.version }} · {{ source.page ? `第${source.page}页` : '文本' }} · 第{{ source.paragraph }}段</span>
      <p>{{ trace.used.includes(source.id) && run?.status === 'SUCCEEDED' ? '回答已引用' : '检索候选' }} · 相似度 {{ source.score.toFixed(3) }}</p>
      <SourcePreview :source="source" :detail="opened[source.id]" />
    </article>
  </section>
</template>

<style scoped>
h2 { font-size: 1.1rem; }
.citation-card { border: 1px solid #ddd; padding: .8rem; margin: .6rem 0; overflow-wrap: anywhere; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
.expired { color: #a33; }
</style>
