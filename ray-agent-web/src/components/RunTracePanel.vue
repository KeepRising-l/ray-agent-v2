<script setup>
defineProps({ run: Object, events: Array })

function detail(event) {
  if (event.type.startsWith('COMPACTION_') || event.type.startsWith('RUN_') || event.type === 'CONTEXT_PROJECTED') {
    try {
      const data = JSON.parse(event.detail)
      return data.beforeTokens != null ? `本地估算 ${data.beforeTokens} → ${data.afterTokens} · ${data.reason}`
        : data.estimatedInputTotal != null ? `累计输入估算 ${data.estimatedInputTotal}` : data.reason || ''
    } catch { return '' }
  }
  if (event.type.startsWith('ROUTE_') || event.type.startsWith('HYBRID_')) {
    try {
      const data = JSON.parse(event.detail)
      return data.target ? `${data.target} · ${data.origin} · ${data.reason}`
        : data.estimatedInputTotal ? `累计输入估算 ${data.estimatedInputTotal}`
        : data.knowledgeStatus ? `资料 ${data.knowledgeStatus} · 网页 ${data.webStatus}` : ''
    } catch { return '' }
  }
  if (event.type.startsWith('RAG_')) {
    try {
      const data = JSON.parse(event.detail)
      if (event.type === 'RAG_EVIDENCE') return `检索候选 ${data.citations.length} 个片段`
      if (event.type === 'RAG_DECISION') return data.answerable ? '资料依据足够' : '资料依据不足'
      if (event.type === 'RAG_VALIDATED') return `实际引用 ${data.sourceIds.join('、')}`
      if (event.type === 'RAG_CONTEXT_SELECTED') return `${data.modelCalls}次生成累计输入估算 ${data.estimatedInputTotal}`
      return data.reason || ''
    } catch { return '' }
  }
  if (!event.type.startsWith('TOOL_') && !event.type.startsWith('MODEL_')) return event.detail
  try {
    const data = JSON.parse(event.detail)
    return `第 ${data.step} 步 ${data.name || '模型'} ${data.code || ''}`
  } catch { return '' }
}
</script>

<template>
  <section v-if="run" aria-label="运行记录">
    <h2>本次运行</h2>
    <p v-if="run.status === 'QUEUED'">已接收并排队，尚未执行模型。</p>
    <p>状态：{{ run.status }} · 渠道：{{ run.channelId }}</p>
    <p>输入估算：{{ run.estimatedInput ?? '未知' }}（本地估算）</p>
    <p>供应商用量：输入 {{ run.inputTokens ?? '未提供' }} / 输出 {{ run.outputTokens ?? '未提供' }}</p>
    <p v-if="run.endedAt">耗时：{{ run.endedAt - run.createdAt }} ms</p>
    <p v-if="run.errorMessage">{{ run.errorMessage }}</p>
    <details>
      <summary>运行编号及关键事件</summary>
      <p>{{ run.id }}</p>
      <ol>
        <li v-for="event in events" :key="event.seq">{{ event.type }} · {{ detail(event) }}</li>
      </ol>
    </details>
  </section>
</template>
