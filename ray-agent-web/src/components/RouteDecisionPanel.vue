<script setup>
import { computed } from 'vue'
import { routeFromEvents } from '../api/routes.js'
const props = defineProps({ events: { type: Array, default: () => [] } })
const decision = computed(() => routeFromEvents(props.events))
const targets = { DIRECT: '直接对话', KNOWLEDGE: '所选知识库', WEB: '网页工具', HYBRID: '知识库与网页组合', CLARIFY: '先澄清需求' }
const reasons = { GREETING: '问候或致谢', EXPLICIT_SCOPE: '明确的资料范围', FRESH_INFORMATION: '需要外部信息',
  CLASSIFIED: '结构化意图分类', NO_KNOWLEDGE_SCOPE: '尚未选择知识库', CLASSIFIER_UNAVAILABLE: '分类失败，安全回退',
  WEB_DISABLED_BY_USER: '遵守本次禁止联网要求', KNOWLEDGE_DISABLED_BY_USER: '遵守本次不读知识库要求', DIRECT_REQUEST: '明确要求直接解释或回答' }
</script>

<template>
  <section v-if="decision" aria-label="AUTO路由">
    <h2>AUTO 本次选择</h2>
    <p>{{ targets[decision.target] }} · {{ reasons[decision.reason] || decision.reason }}</p>
    <p v-if="decision.target === 'HYBRID'">知识库用 [K编号]，网页用 [W编号]；缺失与冲突分别说明。</p>
  </section>
</template>
