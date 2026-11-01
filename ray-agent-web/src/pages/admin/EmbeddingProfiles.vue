<script setup>
import { onMounted, ref } from 'vue'
import { requestJson as api } from '../../api/http.js'
const form = ref(null); const busy = ref(false); const status = ref('')
async function refresh() { try { form.value = { ...await api('/api/admin/embedding'), apiKey: '', clearKey: false } } catch (e) { status.value = e.message } }
async function save() {
  busy.value = true
  try { form.value = { ...await api('/api/admin/embedding', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(form.value) }), apiKey: '', clearKey: false }; status.value = '已保存向量配置。旧向量空间不参与新检索，请对原文档重建索引。' }
  catch (e) { status.value = e.message } finally { busy.value = false }
}
async function probe() {
  busy.value = true
  try { const result = await api('/api/admin/embedding/probe', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ requestId: crypto.randomUUID() }) }); status.value = result.status === 'SUCCEEDED' ? `向量检查通过：${result.result_id} 维` : `检查失败：${result.code}` }
  catch (e) { status.value = e.message } finally { busy.value = false }
}
onMounted(refresh)
</script>
<template>
  <section aria-label="向量配置"><h2>Embedding 配置</h2><p>向量空间由接口、模型、维度与版本共同决定。修改前须结束活动问答、等待补充和入库任务；旧文档需显式重建。</p>
    <form v-if="form" @submit.prevent="save">
      <p>密钥：{{ form.configured ? '已配置' : '未配置' }}</p>
      <label for="embedding-url">Base URL</label><input id="embedding-url" v-model="form.baseUrl" type="url" required :disabled="busy" />
      <label for="embedding-model">向量模型</label><input id="embedding-model" v-model="form.model" required :disabled="busy" />
      <label for="embedding-dimension">维度</label><input id="embedding-dimension" v-model.number="form.dimension" type="number" min="1" max="4096" required :disabled="busy" />
      <label for="embedding-version">人工版本</label><input id="embedding-version" v-model="form.version" required :disabled="busy" />
      <label for="embedding-key">替换密钥（留空保留原值）</label><input id="embedding-key" v-model="form.apiKey" type="password" autocomplete="new-password" :disabled="busy" /><label><input v-model="form.clearKey" type="checkbox" :disabled="busy" />清空密钥</label>
      <button :disabled="busy">保存向量配置</button><button type="button" :disabled="busy" @click="probe">检查向量连通性</button><button type="button" :disabled="busy" @click="refresh">刷新配置</button>
      <details><summary>当前向量空间</summary><p>{{ form.profileId }}</p></details>
    </form><p role="status">{{ status }}</p>
  </section>
</template>
