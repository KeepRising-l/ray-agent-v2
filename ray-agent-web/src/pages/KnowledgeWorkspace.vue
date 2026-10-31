<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { knowledgeRequest as api, documentStates, isProcessing } from '../api/knowledge.js'
import { requestJson } from '../api/http.js'
import DocumentStatusList from '../components/DocumentStatusList.vue'

const bases = ref([])
const kb = ref('')
const name = ref('')
const documents = ref([])
const selectedDocument = ref(null)
const versions = ref([])
const versionId = ref('')
const detail = ref(null)
const chunks = ref([])
const attempts = ref([])
const file = ref(null)
const input = ref(null)
const busy = ref(false)
const status = ref('')
const embedding = ref(null)
const trackedVersion = ref('')
let poll
let disposed = false
let refreshing = false
let selection = 0
let refreshSequence = 0
let versionSequence = 0

function docPath(doc = selectedDocument.value?.id) {
  return `/${kb.value}/documents/${doc}`
}

async function loadVersion() {
  if (!versionId.value || !selectedDocument.value) return
  const selectedBase = kb.value
  const selectedDoc = selectedDocument.value.id
  const selectedVersion = versionId.value
  const token = ++versionSequence
  const path = `${docPath()}/versions/${versionId.value}`
  if (detail.value?.version.id !== selectedVersion) {
    detail.value = null; chunks.value = []; attempts.value = []
  }
  try {
    const [version, content, history] = await Promise.all([api(path), api(path + '/chunks'), requestJson('/api/tasks/documents/' + selectedVersion + '/attempts')])
    if (disposed || token !== versionSequence || selectedBase !== kb.value || selectedDoc !== selectedDocument.value?.id || selectedVersion !== versionId.value) return
    detail.value = version
    chunks.value = content
    attempts.value = history
  } catch (error) {
    if (!disposed && selectedBase === kb.value && selectedDoc === selectedDocument.value?.id && selectedVersion === versionId.value) status.value = error.message
  }
}

async function selectDocument(doc) {
  const token = ++selection
  selectedDocument.value = doc
  detail.value = null
  chunks.value = []
  attempts.value = []
  try {
    const result = await api(docPath(doc.id) + '/versions')
    if (disposed || token !== selection) return
    versions.value = result
    versionId.value = versions.value[0]?.id || ''
    await loadVersion()
  } catch (error) { if (!disposed && token === selection) status.value = error.message }
}

async function refresh(force = false) {
  if (!kb.value || (!force && refreshing) || disposed) return
  const token = ++refreshSequence
  refreshing = true
  const currentBase = kb.value
  try {
    const result = await api(`/${currentBase}/documents`)
    if (disposed || currentBase !== kb.value || token !== refreshSequence) return
    documents.value = result
    const tracked = result.find(doc => doc.versionId === trackedVersion.value)
    if (tracked && !isProcessing(tracked.status)) {
      status.value = tracked.status === 'READY' ? '文档已完成入库，可查看片段和来源。'
        : tracked.status === 'INTERRUPTED' ? '文档处理已中断，可以重试。' : `文档处理失败：${tracked.errorMessage || '请查看版本详情'}`
      trackedVersion.value = ''
    }
    if (selectedDocument.value) {
      const selected = result.find(doc => doc.id === selectedDocument.value.id)
      if (selected) {
        selectedDocument.value = selected
        const next = await api(docPath(selected.id) + '/versions')
        if (disposed || currentBase !== kb.value || token !== refreshSequence || selected.id !== selectedDocument.value?.id) return
        versions.value = next
        await loadVersion()
      } else {
        selectedDocument.value = null; versions.value = []; chunks.value = []; detail.value = null; attempts.value = []
        status.value = '该文档已删除或当前不可访问。'
      }
    }
  } finally {
    if (token === refreshSequence) refreshing = false
  }
}

async function selectBase() {
  ++selection
  ++refreshSequence
  refreshing = false
  trackedVersion.value = ''
  selectedDocument.value = null
  versions.value = []
  detail.value = null
  chunks.value = []
  attempts.value = []
  documents.value = []
  localStorage.setItem('ray-kb', kb.value)
  const selectedBase = kb.value
  if (selectedBase) {
    try {
      const result = await api(`/${selectedBase}/documents`)
      if (!disposed && kb.value === selectedBase) documents.value = result
    } catch (error) { if (!disposed && kb.value === selectedBase) status.value = error.message }
  }
}

async function createBase() {
  busy.value = true
  try {
    const created = await api('', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: name.value.trim() }) })
    bases.value = await api()
    kb.value = created.id
    name.value = ''
    await selectBase()
    if (disposed) return
    status.value = '知识库已建立'
  } catch (error) { status.value = error.message } finally { busy.value = false }
}

async function upload(asVersion = false) {
  if (!file.value || !kb.value) return
  if (file.value.size > 5 * 1024 * 1024) {
    status.value = '文件不能超过 5 MB'
    return
  }
  busy.value = true
  try {
    const body = new FormData()
    body.append('file', file.value)
    const path = asVersion ? `${docPath()}/versions` : `/${kb.value}/documents`
    const uploaded = await api(path, { method: 'POST', body })
    trackedVersion.value = uploaded.id
    status.value = uploaded.status === 'UPLOADED' ? '上传已保存，正在等待后台处理' : `该内容已有版本：${documentStates[uploaded.status]}`
    await refresh(true)
    const doc = documents.value.find(item => item.id === uploaded.documentId)
    if (doc) await selectDocument(doc)
    file.value = null
    if (input.value) input.value.value = ''
  } catch (error) { status.value = error.message } finally { busy.value = false }
}

async function retry() {
  busy.value = true
  try {
    await api(`${docPath()}/versions/${versionId.value}/retry`, { method: 'POST' })
    trackedVersion.value = versionId.value
    status.value = '已重新排入处理队列'
    await refresh(true)
  } catch (error) { status.value = error.message } finally { busy.value = false }
}

async function reindex() {
  busy.value = true
  try {
    const version = await api(`${docPath()}/reindex`, { method: 'POST' })
    trackedVersion.value = version.id
    status.value = '已创建新的索引版本，旧有效版本会保留到重建完成'
    await refresh(true)
    versions.value = await api(docPath() + '/versions')
    versionId.value = version.id
    await loadVersion()
  } catch (error) { status.value = error.message } finally { busy.value = false }
}

async function removeDocument() {
  if (!window.confirm('删除此文档及其索引？历史引用将失效。')) return
  busy.value = true
  try {
    await api(docPath(), { method: 'DELETE' })
    selectedDocument.value = null
    detail.value = null
    chunks.value = []
  attempts.value = []
    versions.value = []
    status.value = '文档和索引已删除，原文件清理失败时会由后台重试'
    await refresh(true)
  } catch (error) { status.value = error.message } finally { busy.value = false }
}

onMounted(async () => {
  try {
    embedding.value = await api('/embedding')
    bases.value = await api()
    kb.value = bases.value.find(base => base.id === localStorage.getItem('ray-kb'))?.id || bases.value[0]?.id || ''
    await selectBase()
    poll = setInterval(async () => {
      if (!busy.value && kb.value) {
        try { await refresh() } catch { status.value = '无法更新文档状态，请检查后端' }
      }
    }, 1000)
  } catch (error) { status.value = error.message }
})

onUnmounted(() => {
  disposed = true
  clearInterval(poll)
})
</script>

<template>
  <section aria-label="知识库管理">
    <h2>知识库与文档</h2>
    <p>上传并查看入库结果。返回聊天页面，新建 KNOWLEDGE 会话后即可根据资料提问。</p>
    <p v-if="embedding">向量模型：{{ embedding.model }}（{{ embedding.dimension }} 维）{{ embedding.configured ? '' : '，尚未配置密钥' }}</p>
    <form @submit.prevent="createBase">
      <label for="kb-name">新知识库名称</label>
      <input id="kb-name" v-model="name" maxlength="100" :disabled="busy" />
      <button :disabled="busy || !name.trim()">创建知识库</button>
    </form>
    <label for="kb-select">知识库</label>
    <select id="kb-select" v-model="kb" :disabled="busy" @change="selectBase">
      <option v-for="base in bases" :key="base.id" :value="base.id">{{ base.name }}</option>
    </select>
    <form v-if="kb" @submit.prevent="upload(false)">
      <label for="document-file">文件（TXT / Markdown / 文本 PDF，最多 5 MB）</label>
      <input id="document-file" ref="input" type="file" accept=".txt,.md,.pdf" :disabled="busy" @change="file = $event.target.files[0]" />
      <p>文档片段将发送给已配置的 Embedding 服务。同名文件作为该文档的新版本，重复内容复用已有版本。</p>
      <button :disabled="busy || !file">上传文档</button>
      <button v-if="selectedDocument" type="button" :disabled="busy || !file" @click="upload(true)">作为选中文档的新版本</button>
    </form>
    <p role="status">{{ status }}</p>
    <DocumentStatusList :documents="documents" :busy="busy" :selected="selectedDocument?.id" @select="selectDocument" />
    <section v-if="detail" aria-label="文档版本详情">
      <h3>{{ selectedDocument.name }}</h3>
      <button :disabled="busy || isProcessing(selectedDocument.status)" @click="reindex">重建索引</button>
      <button :disabled="busy" @click="removeDocument">删除文档</button>
      <label for="document-version">版本</label>
      <select id="document-version" v-model="versionId" :disabled="busy" @change="loadVersion">
        <option v-for="version in versions" :key="version.id" :value="version.id">v{{ version.version }} · {{ documentStates[version.status] }}</option>
      </select>
      <p>{{ documentStates[detail.version.status] }} · {{ detail.version.chunkCount }} 个片段 · 已保存 {{ detail.vectorCount }} 条向量</p>
      <p v-if="selectedDocument.activeVersionId === versionId">此版本为当前有效版本。</p>
      <p v-else-if="selectedDocument.activeVersionId">较早的就绪版本仍有效，本版本尚未替换它。</p>
      <p v-if="detail.version.errorMessage">{{ detail.version.failureStage }}：{{ detail.version.errorMessage }}</p>
      <button v-if="['FAILED', 'INTERRUPTED'].includes(detail.version.status) && versionId === versions[0]?.id" :disabled="busy" @click="retry">重试处理</button>
      <details>
        <summary>处理信息</summary>
        <p>SHA-256：{{ detail.version.sha256 }}</p>
        <p>解析版本：{{ detail.version.parserVersion }}</p>
        <p>分块策略：{{ detail.version.chunkPolicy }}</p>
        <p>向量空间：{{ detail.version.embeddingProfile || '尚未写入' }}</p>
      </details>
      <details v-if="attempts.length"><summary>处理尝试 · {{ attempts.length }} 次</summary>
        <ol><li v-for="attempt in attempts" :key="attempt.attempt">#{{ attempt.attempt }} · {{ attempt.stage }} · {{ attempt.status }} {{ attempt.error_code || '' }}</li></ol>
      </details>
      <h4>片段与来源定位</h4>
      <details v-for="chunk in chunks" :key="chunk.id" class="chunk-preview">
        <summary>#{{ chunk.ordinal + 1 }} · {{ chunk.page ? `第 ${chunk.page} 页` : '文本' }} · 第 {{ chunk.paragraph }} 段 {{ chunk.heading }}</summary>
        <pre>{{ chunk.content }}</pre>
      </details>
    </section>
  </section>
</template>

<style scoped>
label { display: block; margin-top: .8rem; }
button { padding: .4rem .7rem; margin: .4rem; }
input, select { max-width: 100%; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; border: 1px solid #ddd; padding: .8rem; }
p { overflow-wrap: anywhere; }
li { margin: .8rem 0; }
</style>
