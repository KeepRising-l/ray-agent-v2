<script setup>
import { computed } from 'vue'
import { toolTrace } from '../api/toolTrace.js'

const props = defineProps({ events: { type: Array, default: () => [] }, status: String })
const calls = computed(() => toolTrace(props.events, props.status))
const labels = { RUNNING: '执行中', SUCCEEDED: '成功', FAILED: '失败', INTERRUPTED: '执行已中断' }
const reasons = {
  INVALID_ARGUMENTS: '参数不符合工具定义', FORBIDDEN: '本次运行不允许调用该工具',
  UNKNOWN_TOOL: '工具未注册', TIMEOUT: '工具请求超时', NOT_CONFIGURED: '搜索服务尚未配置',
  URL_OR_CONTENT_REJECTED: '地址被访问策略拒绝，或网页格式暂不支持', NO_RESULTS: '没有找到可用网页',
  EMPTY_CONTENT: '网页没有可提取的正文', REDIRECT_LIMIT: '网页重定向次数过多或目标缺失',
  SEARCH_HTTP_ERROR: '搜索服务返回错误', FETCH_HTTP_ERROR: '网页返回错误', BUSY: '工具执行容量已满',
}
</script>

<template>
  <section v-if="calls.length" aria-label="工具执行">
    <h2>工具执行</h2>
    <article v-for="call in calls" :key="call.callId" class="tool-card" :data-status="call.status">
      <strong>{{ call.name === 'web_search' ? '网页搜索' : call.name === 'web_fetch' ? '网页抓取' : call.name }}</strong>
      <span> · {{ labels[call.status] }} · 第 {{ call.step }} 步</span>
      <p v-if="call.status !== 'RUNNING'">{{ call.status === 'SUCCEEDED' ? '执行完成' : call.status === 'INTERRUPTED' ? '运行已结束' : (reasons[call.code] || '工具执行失败') }} · {{ call.durationMs || 0 }} ms</p>
      <ul v-if="call.sources.length">
        <li v-for="source in call.sources" :key="source.id + source.kind">
          <a :href="source.url" target="_blank" rel="noopener noreferrer">[{{ source.id }}] {{ source.title || source.url }}</a>
          <small> · {{ source.kind === 'FETCH' ? '已抓取正文' : '搜索摘要' }}</small>
        </li>
      </ul>
    </article>
  </section>
</template>

<style scoped>
h2 { font-size: 1.1rem; }
.tool-card { border: 1px solid #ddd; border-radius: 6px; margin: .6rem 0; padding: .8rem; overflow-wrap: anywhere; }
[data-status="FAILED"], [data-status="INTERRUPTED"] { border-color: #b66; }
p { margin: .4rem 0; font-size: .9rem; }
li { margin: .4rem 0; }
</style>
