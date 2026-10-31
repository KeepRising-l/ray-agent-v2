<script setup>
import ContextUsageView from './ContextUsageView.vue'
import RunEventTimeline from './RunEventTimeline.vue'
defineProps({ run: Object, events: Array })
</script>
<template>
  <section v-if="run" aria-label="运行记录">
    <h2>本次运行</h2>
    <p v-if="run.status === 'QUEUED'">已接收并排队，尚未执行模型。</p>
    <p>状态：{{ run.status }} · 渠道：{{ run.channelId }}</p>
    <ContextUsageView :run="run" />
    <RunEventTimeline :events="events" />
    <details><summary>运行编号</summary><p>{{ run.id }}</p></details>
  </section>
</template>
