<script setup>
import { onMounted, ref } from 'vue'
import { requestJson as api } from '../../api/http.js'
import ModelChannels from './ModelChannels.vue'
import EmbeddingProfiles from './EmbeddingProfiles.vue'
import ToolsAndRoutes from './ToolsAndRoutes.vue'
import RunObservability from './RunObservability.vue'
import TaskPanel from '../../components/TaskPanel.vue'
import KnowledgeWorkspace from '../KnowledgeWorkspace.vue'
defineEmits(['open-session'])
const tab = ref('overview'); const overview = ref(null); const audit = ref([]); const error = ref(''); const target = ref(null)
const tabs = { overview: '概览', models: '模型渠道', embedding: '向量配置', knowledge: '知识库', tasks: '任务补偿', policy: '工具与路由', runs: '运行观察', audit: '操作记录' }
async function select(id) {
  tab.value = id; error.value = ''
  try { if (id === 'overview') overview.value = await api('/api/admin/overview'); if (id === 'audit') audit.value = await api('/api/admin/audit') }
  catch (e) { error.value = e.message }
}
function openKnowledge(value) { target.value = value; select('knowledge') }
onMounted(() => select('overview'))
</script>
<template>
  <section aria-label="个人管理后台" class="admin-workspace"><h2>个人管理后台</h2>
    <nav aria-label="后台分类"><button v-for="(name, id) in tabs" :key="id" :aria-current="tab === id ? 'page' : undefined" @click="select(id)">{{ name }}</button></nav>
    <p v-if="error" role="alert">{{ error }}</p>
    <section v-if="tab === 'overview' && overview" aria-label="后台概览"><h3>{{ overview.username }} 的管理概览</h3><p>知识库 {{ overview.knowledgeBases }} 个 · 未删除文档 {{ overview.documents }} 个</p><p>本进程执行 {{ overview.capacity.running }}/{{ overview.capacity.runningLimit }} · 排队 {{ overview.capacity.queued }}/{{ overview.capacity.queueLimit }} · 拒绝 {{ overview.capacity.rejected }} 次</p><p>默认只监听本机；部署与多人权限尚未接入。</p></section>
    <ModelChannels v-if="tab === 'models'" /><EmbeddingProfiles v-if="tab === 'embedding'" />
    <KnowledgeWorkspace v-if="tab === 'knowledge'" :admin="true" :initial-target="target" />
    <TaskPanel v-if="tab === 'tasks'" :admin="true" @open-session="$emit('open-session', $event)" @open-knowledge="openKnowledge" />
    <ToolsAndRoutes v-if="tab === 'policy'" /><RunObservability v-if="tab === 'runs'" />
    <section v-if="tab === 'audit'" aria-label="后台操作记录"><h3>最近100条操作</h3><p>配置修改、检查与补偿分别记录；REQUESTED表示尚未确认完成，中断记录不会自动重放。</p>
      <article v-for="item in audit" :key="item.id" class="audit-row"><strong>{{ item.action }} · {{ item.status }}</strong><p>操作者 {{ item.actor }} · {{ new Date(item.created_at).toLocaleString() }}</p><p>目标 {{ item.target }} · {{ item.code || '处理中' }} · {{ item.result_id || '' }}</p></article>
      <p v-if="!audit.length">暂无后台操作记录。</p>
    </section>
  </section>
</template>
<style scoped>.admin-workspace :deep(form) { max-width: 720px; }.admin-workspace :deep(input:not([type='checkbox'])) { width: 100%; }.audit-row { border-bottom: 1px solid #ddd; padding: .8rem 0; }</style>
