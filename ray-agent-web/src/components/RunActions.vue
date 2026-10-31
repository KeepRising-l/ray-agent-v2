<script setup>
defineProps({ active: Object, busy: Boolean, failed: Boolean, uncertain: Boolean })
defineEmits(['cancel', 'refresh', 'edit'])
</script>
<template>
  <div class="run-actions">
    <button v-if="active || busy" type="button" @click="$emit('cancel')">停止</button>
    <button v-if="uncertain" type="button" @click="$emit('refresh')">查询本次请求</button>
    <button v-else type="button" @click="$emit('refresh')">刷新状态</button>
    <button v-if="failed && !active && !busy && !uncertain" type="button" @click="$emit('edit')">编辑后重新提问</button>
  </div>
</template>
