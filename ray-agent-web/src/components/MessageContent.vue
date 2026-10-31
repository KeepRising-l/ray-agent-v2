<script setup>
import { computed } from 'vue'
const props = defineProps({ content: String, sources: Boolean, disabled: Boolean })
defineEmits(['source'])
const parts = computed(() => (props.content || '').split(/(\[(?:K|W)\d+\])/g))
</script>
<template>
  <p class="answer"><template v-for="(part, index) in parts" :key="index"><button v-if="sources && /^\[(K|W)\d+\]$/.test(part)" type="button" class="inline-source" :aria-label="`查看此回答的 ${part.slice(1, -1)} 来源`" :disabled="disabled" @click="$emit('source', part.slice(1, -1))">{{ part }}</button><template v-else>{{ part }}</template></template></p>
</template>
<style scoped>.answer { white-space: pre-wrap; overflow-wrap: anywhere; }.inline-source { display: inline; padding: 0 .12rem; margin: 0; color: #294e71; border: 0; background: transparent; text-decoration: underline; }</style>
