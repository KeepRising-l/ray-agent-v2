<script setup>
import { computed } from 'vue'
import { timeline } from '../api/runEvents.js'
const props = defineProps({ events: Array })
const rows = computed(() => timeline(props.events))
</script>
<template>
  <details v-if="rows.length" class="run-timeline"><summary>运行过程 · {{ rows.length }} 条事件</summary>
    <ol><li v-for="event in rows" :key="event.seq"><strong>{{ event.label }}</strong><span v-if="event.description"> · {{ event.description }}</span></li></ol>
  </details>
</template>
