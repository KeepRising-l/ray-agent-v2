<script setup>
defineProps({ run: Object, events: Array })

function detail(event) {
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
