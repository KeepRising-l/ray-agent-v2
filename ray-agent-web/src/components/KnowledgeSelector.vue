<script setup>
defineProps({ bases: Array, disabled: Boolean })
const selected = defineModel({ type: Array, default: () => [] })
</script>

<template>
  <fieldset :disabled="disabled">
    <legend>新会话使用的知识库（最多5个）</legend>
    <label v-for="base in bases" :key="base.id">
      <input v-model="selected" type="checkbox" :value="base.id" :disabled="!selected.includes(base.id) && selected.length >= 5" />{{ base.name }}
    </label>
    <p v-if="!bases?.length">请先在知识库管理中创建知识库并上传文档。</p>
  </fieldset>
</template>
