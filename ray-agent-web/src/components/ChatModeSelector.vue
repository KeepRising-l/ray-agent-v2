<script setup>
defineProps({ modelValue: String, disabled: Boolean })
defineEmits(['update:modelValue'])
</script>

<template>
  <label for="new-mode">新会话模式</label>
  <select id="new-mode" :value="modelValue" :disabled="disabled" @change="$emit('update:modelValue', $event.target.value)">
    <option value="CHAT">CHAT：智能对话，可按需联网</option>
    <option value="KNOWLEDGE">KNOWLEDGE：只根据知识库回答</option>
    <option value="AUTO">AUTO：自动选择或组合资料与网页</option>
  </select>
</template>
