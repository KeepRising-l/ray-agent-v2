<script setup>defineProps({ error: Object, run: Object })</script>
<template>
  <div v-if="error || (run && ['FAILED', 'INTERRUPTED', 'TIMED_OUT', 'CANCELLED'].includes(run.status))" class="error-notice" role="alert">
    <p>{{ error?.message || run.errorMessage || '本次运行未完成。' }}</p>
    <p v-if="error?.uncertain">接收结果尚未确认，请先查询原请求，避免重复执行。</p>
    <p v-else-if="run && run.status !== 'SUCCEEDED'">已保存的部分内容保留为未完成；重新提问将创建新运行。</p>
  </div>
</template>
<style scoped>.error-notice { border-left: 3px solid #9b4838; padding: .2rem .8rem; margin: 1rem 0; }p { margin: .4rem 0; }</style>
