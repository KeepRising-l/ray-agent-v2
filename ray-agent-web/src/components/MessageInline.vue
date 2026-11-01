<script setup>
import { inlineParts } from '../api/messageFormat.js'
defineProps({ text: String, sources: Boolean, disabled: Boolean })
defineEmits(['source'])
</script>
<template><template v-for="(part, index) in inlineParts(text)" :key="index"><button v-if="sources && part.type === 'source'" type="button" class="inline-source" :aria-label="`查看此回答的 ${part.id} 来源`" :disabled="disabled" @click="$emit('source', part.id)">{{ part.text }}</button><strong v-else-if="part.type === 'strong'">{{ part.text }}</strong><code v-else-if="part.type === 'code'" class="inline-code">{{ part.text }}</code><template v-else>{{ part.text }}</template></template></template>
