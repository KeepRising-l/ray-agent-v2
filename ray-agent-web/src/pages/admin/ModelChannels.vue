<script setup>
import { onMounted, reactive, ref } from 'vue'
import { requestJson as api } from '../../api/http.js'
const catalogue = ref(null)
const selected = ref('')
const busy = ref(false)
const status = ref('')
const form = reactive({ id: '', baseUrl: '', model: '', enabled: true, makeDefault: false, apiKey: '', clearKey: false })
function choose() {
  const channel = catalogue.value.channels.find(c => c.id === selected.value)
  Object.assign(form, { id: channel?.id || '', baseUrl: channel?.baseUrl || '', model: channel?.model || '', enabled: channel?.enabled ?? true, makeDefault: channel?.id === catalogue.value.defaultChannel, apiKey: '', clearKey: false })
}
async function refresh() {
  try { catalogue.value = await api('/api/admin/models'); if (!selected.value) selected.value = catalogue.value.channels[0]?.id || ''; choose() }
  catch (e) { status.value = e.message }
}
async function save() {
  busy.value = true
  try {
    catalogue.value = await api('/api/admin/models/'+encodeURIComponent(form.id), { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...form, revision: catalogue.value.revision }) })
    selected.value = form.id; choose(); status.value = '已保存，新运行使用新配置；历史保留原快照。'
  } catch (e) { status.value = e.message }
  finally { busy.value = false }
}
async function probe() {
  busy.value = true
  try { const result = await api('/api/admin/models/'+encodeURIComponent(selected.value)+'/probe', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ requestId: crypto.randomUUID() }) }); status.value = result.status === 'SUCCEEDED' ? '模型连通性检查通过' : `检查未成功：${result.code}` }
  catch (e) { status.value = e.message } finally { busy.value = false }
}
onMounted(refresh)
</script>
<template>
  <section aria-label="模型配置"><h2>模型渠道</h2>
    <p>修改只影响新运行；正在输出的连接保留原适配器。检查会发送一条短请求，可能产生用量。</p>
    <template v-if="catalogue">
      <label for="admin-model-channel">选择渠道</label><select id="admin-model-channel" v-model="selected" :disabled="busy" @change="choose"><option value="">新增渠道</option><option v-for="channel in catalogue.channels" :key="channel.id" :value="channel.id">{{ channel.id }} · {{ channel.model }}{{ catalogue.defaultChannel === channel.id ? ' · 默认' : '' }}{{ channel.configured ? '' : ' · 缺少密钥' }}</option></select>
      <form @submit.prevent="save">
        <label for="admin-model-id">渠道编号</label><input id="admin-model-id" v-model="form.id" pattern="[a-z0-9-]{1,40}" required :disabled="busy || !!selected" />
        <label for="admin-model-url">Base URL</label><input id="admin-model-url" v-model="form.baseUrl" type="url" required :disabled="busy" />
        <label for="admin-model-name">模型名称</label><input id="admin-model-name" v-model="form.model" maxlength="200" required :disabled="busy" />
        <label for="admin-model-key">替换密钥（留空保留原值）</label><input id="admin-model-key" v-model="form.apiKey" type="password" autocomplete="new-password" :disabled="busy" />
        <label><input v-model="form.clearKey" type="checkbox" :disabled="busy" />清空此渠道密钥</label>
        <label><input v-model="form.enabled" type="checkbox" :disabled="busy" />启用渠道</label><label><input v-model="form.makeDefault" type="checkbox" :disabled="busy" />设为默认</label>
        <button :disabled="busy">保存模型配置</button><button type="button" :disabled="busy || !selected" @click="probe">检查模型连通性</button><button type="button" :disabled="busy" @click="refresh">刷新配置</button>
      </form>
    </template><p role="status">{{ status }}</p>
  </section>
</template>
