<script setup>
import { onMounted, ref } from 'vue'
import { requestJson as api } from '../../api/http.js'
const form = ref(null); const status = ref(''); const busy = ref(false)
async function refresh() { try { form.value = await api('/api/admin/policy') } catch (e) { status.value = e.message } }
async function save() {
  busy.value = true
  try { form.value = await api('/api/admin/policy', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(form.value) }); status.value = '策略已保存，对新运行生效。' }
  catch (e) { status.value = e.message } finally { busy.value = false }
}
onMounted(refresh)
</script>
<template>
  <section aria-label="工具与路由策略"><h2>工具与路由</h2>
    <p>CHAT不读知识库；KNOWLEDGE不联网；AUTO只能组合原授权范围。策略无法扩大这些边界。运行中的预算和允许名单保留快照。</p>
    <form v-if="form" @submit.prevent="save">
      <label><input v-model="form.searchEnabled" type="checkbox" :disabled="busy" />允许网页搜索</label><label><input v-model="form.fetchEnabled" type="checkbox" :disabled="busy" />允许网页抓取</label>
      <label><input v-model="form.autoEnabled" type="checkbox" :disabled="busy" />允许新AUTO运行</label><label><input v-model="form.classifierEnabled" type="checkbox" :disabled="busy" />允许AUTO模型分类（关闭后使用确定规则或澄清）</label>
      <label for="policy-model-steps">最多模型调用</label><input id="policy-model-steps" v-model.number="form.maxModelSteps" type="number" min="1" max="10" required :disabled="busy" />
      <label for="policy-tools">最多工具调用</label><input id="policy-tools" v-model.number="form.maxToolCalls" type="number" min="0" max="20" required :disabled="busy" />
      <label for="policy-timeout">活跃执行时限（毫秒）</label><input id="policy-timeout" v-model.number="form.timeoutMs" type="number" min="100" max="600000" required :disabled="busy" />
      <label for="policy-tool-timeout">单次工具时限（毫秒）</label><input id="policy-tool-timeout" v-model.number="form.toolTimeoutMs" type="number" min="1" max="600000" required :disabled="busy" />
      <button :disabled="busy">保存运行策略</button><button type="button" :disabled="busy" @click="refresh">刷新策略</button>
    </form><p role="status">{{ status }}</p>
  </section>
</template>
