<script setup>
import { onMounted, ref } from 'vue'
import { requestJson as api } from '../../api/http.js'
import RunTracePanel from '../../components/RunTracePanel.vue'
const report = ref(null); const sessions = ref([]); const snapshot = ref(null); const selected = ref(null); const events = ref([]); const error = ref('')
let selection = 0
async function refresh() { try { [report.value, sessions.value] = await Promise.all([api('/api/admin/statistics'), api('/api/admin/sessions')]); error.value = '' } catch (e) { error.value = e.message } }
async function inspect(id) {
  const token = ++selection
  snapshot.value = null
  try {
    const [run, timeline] = await Promise.all([api('/api/runs/'+id), api('/api/runs/'+id+'/events')])
    if (token !== selection) return
    selected.value = run; events.value = timeline
    const settings = await api('/api/admin/runs/'+id+'/snapshot')
    if (token !== selection) return
    snapshot.value = settings; error.value = ''
  } catch (e) { if (token === selection) error.value = e.message }
}
onMounted(refresh)
</script>
<template>
  <section aria-label="运行观察"><h2>运行与用量</h2><button @click="refresh">刷新统计</button><p role="status">{{ error }}</p>
    <template v-if="report">
      <p>{{ report.basis }}</p><p>运行 {{ report.totals.runs }} 次 · 缺失供应商用量 {{ report.totals.runs_with_missing_usage }} 次</p>
      <p>已知输入总和：{{ report.totals.known_input_tokens ?? '未提供' }} · 已知输出总和：{{ report.totals.known_output_tokens ?? '未提供' }}</p>
      <p>完成运行平均总耗时（含排队/等待）：{{ report.totals.completed_average_total_ms ?? '暂无' }} ms</p>
      <h3>状态分布</h3><ul><li v-for="row in report.states" :key="row.status">{{ row.status }} · {{ row.count }}</li></ul>
      <h3>实际配置模型</h3><ul><li v-for="row in report.models" :key="row.channel_id + row.configured_model">{{ row.channel_id }} / {{ row.configured_model }} · {{ row.count }}</li></ul>
      <h3>事件计数</h3><p>TOOL_STARTED为尝试数，TOOL_SUCCEEDED为成功返回数；未结束的工具不会被补算成成功。</p><ul><li v-for="row in report.events" :key="row.type">{{ row.type }} · {{ row.count }}</li></ul>
      <h3>路由决策</h3><ul><li v-for="row in report.routes" :key="row.target">{{ row.target }} · {{ row.count }}</li></ul>
    </template>
    <h3>会话最新运行</h3><ul><li v-for="session in sessions" :key="session.sessionId">{{ session.title }} · {{ session.mode }} · {{ session.status }} <button v-if="session.latestRunId" @click="inspect(session.latestRunId)">查看运行与配置快照</button></li></ul>
    <RunTracePanel :run="selected" :events="events" />
    <details v-if="snapshot" open><summary>该运行的配置快照</summary><p>{{ snapshot.channelId }} · {{ snapshot.model }} · {{ snapshot.baseUrl }}</p><p>策略版本 {{ snapshot.policy.revision }} · 模型上限 {{ snapshot.policy.maxModelSteps }} · 工具上限 {{ snapshot.policy.maxToolCalls }}</p></details>
  </section>
</template>
