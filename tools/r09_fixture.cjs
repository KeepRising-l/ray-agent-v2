// R09交互验收专用HTTP夹具：模拟API，不连接数据库或模型，不包含用户资料。
const http = require('node:http')
const { randomUUID } = require('node:crypto')
const sessions = [
  { id: 'session-a', title: 'Java接口学习', mode: 'CHAT', knowledgeBaseIds: [] },
  { id: 'session-b', title: '巡检资料问答', mode: 'KNOWLEDGE', knowledgeBaseIds: ['kb-one'] },
]
const messages = { 'session-a': [{ id: 'ma', role: 'USER', content: '接口有哪些用途？', complete: true }], 'session-b': [{ id: 'mb', runId: 'run-b', role: 'ASSISTANT', content: '巡检编号是 Maple52。[K1]', complete: true }] }
const runs = { 'session-a': [], 'session-b': [{ id: 'run-b', sessionId: 'session-b', status: 'SUCCEEDED', channelId: 'local', estimatedInput: 80, inputTokens: null, outputTokens: null, createdAt: 1, endedAt: 2 }] }
const bases = [{ id: 'kb-one', name: '巡检资料' }]
const events = [{ seq: 1, type: 'RAG_EVIDENCE', detail: JSON.stringify({ citations: [{ id: 'K1', documentName: '巡检手册.pdf', version: 1, page: 2, paragraph: 3, score: .89, documentId: 'doc-one', versionId: 'version-one', chunkId: 'chunk-one' }] }) }, { seq: 2, type: 'RAG_VALIDATED', detail: '{"sourceIds":["K1"]}' }]
const allEvents = { 'run-b': events }; const questions = {}; const requests = new Map(); const responses = new Map()
const doc = { id: 'doc-one', name: '巡检手册.pdf', latestVersion: 2, activeVersionId: 'version-one', versionId: 'version-two', status: 'FAILED', chunkCount: 1, errorMessage: '文档解析失败，请检查文件' }
const versions = [{ id: 'version-two', documentId: doc.id, version: 2, status: 'FAILED', chunkCount: 0, failureStage: 'PARSING', errorMessage: doc.errorMessage }, { id: 'version-one', documentId: doc.id, version: 1, status: 'READY', chunkCount: 1 }]
let deleted = false
function sse(res, name, payload) { res.write('event:'+name+'\ndata:'+JSON.stringify(payload)+'\n\n') }
function start(port = 0, { richChat = false } = {}) {
  if (richChat && messages['session-a'].length === 1) {
    messages['session-a'].push({ id: 'ui-reading-answer', role: 'ASSISTANT', complete: true, content: '可以把接口理解成一份**能力约定**：它说明一个对象能做什么。\n\n### 用一个具体例子\n假设我们需要接入不同的模型供应商，可以先约定一个统一入口：\n\n```java\npublic interface LlmAdapter {\n    Publisher<StreamChunk> generate(GenerateRequest request);\n}\n```\n\n- 调用方依赖这个接口，不必知道请求最终发往哪里。\n- 不同供应商各自完成协议适配。\n- 测试时，可以换成一个可控的模拟实现。\n\n这样，**调用关系保持稳定，具体实现可以替换**。如果你愿意，我们可以接着拿一次真实请求走一遍。' })
  }
  const server = http.createServer(async (req, res) => {
    const path = req.url; let body = ''; for await (const chunk of req) body += chunk
    const send = (value, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value)) }
    if (path === '/api/model-channels') return send([{ id: 'local', model: 'fixture-chat' }])
    if (path === '/api/auth/session') return send({ authenticated: true, username: 'fixture-owner', csrfToken: 'fixture-csrf', csrfHeader: 'X-CSRF-TOKEN' })
    if (path === '/api/knowledge-bases') return send(bases)
    if (path === '/api/sessions/status') return send([])
    if (path === '/api/tasks/capacity') return send({ runningLimit: 2, queueLimit: 8, running: 0, queued: 0, admitted: 0, rejected: 0 })
    if (path === '/api/tasks') return send([])
    if (path === '/api/chat/stream') {
      const input = JSON.parse(body)
      if (input.message.includes('过载')) return send({ message: '容量已满' }, 429)
      if (requests.has(input.clientRequestId)) {
        const run = requests.get(input.clientRequestId); return send({ message: '请求已接收', runId: run.id, status: run.status, sameInput: true }, 409)
      }
      const run = { id: randomUUID(), sessionId: input.sessionId, status: 'RUNNING', channelId: input.channelId, createdAt: Date.now(), inputTokens: null, outputTokens: null }
      requests.set(input.clientRequestId, run); runs[input.sessionId].unshift(run)
      messages[input.sessionId].push({ id: randomUUID(), runId: run.id, role: 'USER', content: input.message, complete: true })
      allEvents[run.id] = [{ seq: 1, type: 'QUEUED', detail: '{}' }]
      if (input.message.includes('断线')) {
        run.status = 'SUCCEEDED';run.endedAt = Date.now()
        messages[input.sessionId].push({ id: randomUUID(), runId: run.id, role: 'ASSISTANT', content: '服务端已保存，查询原请求即可。', complete: true })
        req.socket.destroy();return
      }
      res.writeHead(200, { 'Content-Type': 'text/event-stream' })
      sse(res, 'meta', { runId: run.id, sessionId: input.sessionId, status: 'QUEUED' });sse(res, 'queue', { status: 'QUEUED' })
      if (input.message.includes('排队')) { run.status = 'QUEUED'; responses.set(run.id, res); return }
      sse(res, 'queue', { status: 'RUNNING' })
      if (input.message.includes('等待')) {
        run.status = 'WAITING_USER'
        questions[run.id] = { id: randomUUID(), runId: run.id, status: 'WAITING_USER', question: '你的目标城市是哪里？', expiresAt: Date.now()+86400000 }
        sse(res, 'question', questions[run.id]);res.end();return
      }
      const failed = input.message.includes('失败');run.status = failed ? 'FAILED' : 'SUCCEEDED';run.endedAt = Date.now()
      const text = failed ? '已输出的部分答案' : '已完成：'+input.message
      messages[input.sessionId].push({ id: randomUUID(), runId: run.id, role: 'ASSISTANT', content: text, complete: !failed })
      if (input.message.includes('摘要')) allEvents[run.id].push({ seq: 2, type: 'COMPACTION_APPLIED', detail: '{"beforeTokens":1000,"afterTokens":300}' })
      allEvents[run.id].push({ seq: 3, type: run.status, detail: '{}' })
      sse(res, 'text', { text }); sse(res, failed ? 'error' : 'done', failed ? { runId: run.id, status: 'FAILED', message: '模型暂时不可用' } : { reason: 'STOP', status: run.status, runId: run.id });res.end();return
    }
    if (path === '/api/sessions' && req.method === 'POST') {
      const input = JSON.parse(body); const session = { ...input, id: randomUUID() }; sessions.unshift(session); messages[session.id] = []; runs[session.id] = []; return send(session)
    }
    if (path === '/api/sessions') return send(sessions)
    const sessionMatch = path.match(/^\/api\/sessions\/([^/]+)\/(messages|runs)$/)
    if (sessionMatch) return send((sessionMatch[2] === 'messages' ? messages : runs)[sessionMatch[1]] || [])
    if (path.includes('/requests/')) { const run = requests.get(path.split('/').at(-1)); return send(run || { message: '尚未接收' }, run ? 200 : 404) }
    const runMatch = path.match(/^\/api\/runs\/([^/]+)(?:\/(.*))?$/)
    if (runMatch) {
      const id = runMatch[1];const action = runMatch[2];const run = Object.values(runs).flat().find(row => row.id === id)
      if (action === 'question') return send({ question: questions[id] || null })
      if (action === 'events') return send(allEvents[id] || [])
      if (action?.startsWith('citations/')) return send({ valid: !deleted, text: deleted ? '' : '第2页原文：巡检编号为 Maple52。' })
      if (action === 'answers' && req.method === 'POST') {
        const input = JSON.parse(body);const question = questions[id]
        if (!question || question.id !== input.questionId || question.status !== 'WAITING_USER') return send({ message: '已消费的提问' }, 409)
        question.status = 'ANSWERED';question.answer = input.answer;run.status = 'SUCCEEDED';run.endedAt = Date.now()
        messages[run.sessionId].push({ id: randomUUID(), runId: id, role: 'ASSISTANT', content: '欢迎来到'+input.answer, complete: true })
        res.writeHead(200, { 'Content-Type': 'text/event-stream' });sse(res, 'meta', { runId: id, sessionId: run.sessionId, status: 'QUEUED' });sse(res, 'text', { text: '欢迎来到'+input.answer });sse(res, 'done', { status: 'SUCCEEDED', reason: 'STOP' });res.end();return
      }
      if (action === 'cancel') {
        run.status = 'CANCELLED';run.endedAt = Date.now();if (questions[id]) questions[id].status = 'CANCELLED'
        const stream = responses.get(id);if (stream) { sse(stream, 'done', { status: 'CANCELLED', reason: 'CANCELLED' });stream.end();responses.delete(id) }
        return send(run)
      }
      if (!action) return send(run)
    }
    if (path === '/api/knowledge-bases/embedding') return send({ model: 'fixture-embedding', dimension: 3, configured: false })
    if (path === '/api/knowledge-bases/kb-one/documents') return send(deleted ? [] : [doc])
    if (path === '/api/tasks/documents/version-two/attempts') return send([{ attempt: 1, stage: 'PARSING', status: 'FAILED', error_code: 'PARSER_OR_POLICY' }])
    if (path === '/api/tasks/documents/version-one/attempts') return send([{ attempt: 1, stage: 'PUBLISHED', status: 'SUCCEEDED' }])
    const prefix = '/api/knowledge-bases/kb-one/documents/doc-one'
    if (path === prefix && req.method === 'DELETE') { deleted = true;return send({ deleted: true }) }
    if (path === prefix+'/versions') return send(versions)
    if (path === prefix+'/versions/version-two/retry') {
      doc.status = 'UPLOADED';versions[0].status = 'UPLOADED';versions[0].errorMessage = null
      setTimeout(() => { if (!deleted) { doc.status = 'READY';doc.activeVersionId = 'version-two';doc.errorMessage = null;versions[0].status = 'READY';versions[0].chunkCount = 1 } }, 200)
      return send(versions[0])
    }
    if (path.startsWith(prefix+'/versions/')) {
      const version = versions.find(row => path.includes('/'+row.id))
      if (path.endsWith('/chunks')) return send(version.status === 'READY' ? [{ id: 'chunk-one', ordinal: 0, page: 2, paragraph: 3, heading: '巡检', content: '第2页原文：巡检编号为 Maple52。' }] : [])
      return send({ version, vectorCount: version.status === 'READY' ? 1 : 0 })
    }
    return send({ message: '夹具未实现此接口' }, 404)
  })
  return new Promise(resolve => server.listen(port, '127.0.0.1', () => resolve(server)))
}
module.exports = { start }
if (require.main === module) start(Number(process.argv[2] || 0)).then(server => console.log('R09_FIXTURE_PORT='+server.address().port))
