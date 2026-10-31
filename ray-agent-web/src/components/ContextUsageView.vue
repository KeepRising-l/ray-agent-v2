<script setup>defineProps({ run: Object })</script>
<template>
  <dl v-if="run" class="usage-view">
    <div><dt>本地输入估算</dt><dd>{{ run.estimatedInput ?? '未知' }}</dd></div>
    <div><dt>供应商输入 / 输出</dt><dd>{{ run.inputTokens ?? '未提供' }} / {{ run.outputTokens ?? '未提供' }}</dd></div>
    <div v-if="run.endedAt"><dt>总耗时（含排队与等待）</dt><dd>{{ run.endedAt - run.createdAt }} ms</dd></div>
  </dl>
</template>
<style scoped>.usage-view { font-size: .94rem; }.usage-view div { display: flex; gap: 1rem; flex-wrap: wrap; }dd { margin: 0; }</style>
