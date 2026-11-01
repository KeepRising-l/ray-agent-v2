<script setup>
import { computed, onUnmounted, ref } from 'vue'
import { messageBlocks } from '../api/messageFormat.js'
import MessageInline from './MessageInline.vue'
import UiIcon from './UiIcon.vue'
const props = defineProps({ content: String, sources: Boolean, disabled: Boolean, plain: Boolean })
defineEmits(['source'])
const blocks = computed(() => props.plain ? [{ type: 'paragraph', text: props.content }] : messageBlocks(props.content))
const copied = ref(-1)
const copyError = ref(-1)
let timer
async function copy(text, index) {
  clearTimeout(timer)
  try { await navigator.clipboard.writeText(text); copied.value = index; copyError.value = -1 }
  catch { copied.value = -1; copyError.value = index }
  timer = setTimeout(() => { copied.value = -1; copyError.value = -1 }, 2000)
}
onUnmounted(() => clearTimeout(timer))
</script>
<template><div class="message-content">
  <template v-for="(block, index) in blocks" :key="index">
    <div v-if="block.type === 'code'" class="code-block"><div class="code-heading"><span>{{ block.language || '代码' }}</span><button type="button" @click="copy(block.text, index)"><UiIcon :name="copied === index ? 'check' : 'copy'" :size="14"/>{{ copied === index ? '已复制' : copyError === index ? '复制失败' : '复制代码' }}</button></div><pre><code>{{ block.text }}</code></pre></div>
    <component :is="'h' + block.level" v-else-if="block.type === 'heading'"><MessageInline :text="block.text" :sources="sources" :disabled="disabled" @source="$emit('source', $event)"/></component>
    <component :is="block.type === 'ordered' ? 'ol' : 'ul'" v-else-if="block.type === 'list' || block.type === 'ordered'"><li v-for="(item, n) in block.items" :key="n"><MessageInline :text="item" :sources="sources" :disabled="disabled" @source="$emit('source', $event)"/></li></component>
    <blockquote v-else-if="block.type === 'quote'"><MessageInline :text="block.text" :sources="sources" :disabled="disabled" @source="$emit('source', $event)"/></blockquote>
    <p v-else class="answer"><template v-if="plain">{{ block.text }}</template><MessageInline v-else :text="block.text" :sources="sources" :disabled="disabled" @source="$emit('source', $event)"/></p>
  </template>
</div></template>
