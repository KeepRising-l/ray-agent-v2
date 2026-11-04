import test from 'node:test'
import assert from 'node:assert/strict'
import { createChatSession } from './chatSession.js'
import { RequestError } from '../api/http.js'

const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const sessions = [{ id: 'a', mode: 'CHAT', title: 'a', knowledgeBaseIds: [] }, { id: 'b', mode: 'KNOWLEDGE', title: 'b', knowledgeBaseIds: ['kb'] }]
function harness(overrides = {}, stream) {
  const calls = []
  const data = {
    '/api/sessions': sessions, '/api/knowledge-bases': [{ id: 'kb', name: '资料' }], '/api/model-channels': [{ id: 'local' }],
    '/api/sessions/a/messages': [{ id: 'ma', role: 'USER', content: 'A消息' }], '/api/sessions/b/messages': [{ id: 'mb', content: 'B消息' }],
    '/api/sessions/a/runs': [], '/api/sessions/b/runs': [], ...overrides,
  }
  const api = async (path, options) => { calls.push([path, options]); const value = data[path]; if (value instanceof Error) throw value; return typeof value === 'function' ? value(options) : await value }
  const chat = createChatSession({ api, stream, storage: { getItem: () => null, setItem: () => {} } })
  return { chat, calls, data }
}

test('迟到的A会话响应不能覆盖已选B，切换立即清除旧正文', async () => {
  const slow = deferred(); const { chat } = harness({ '/api/sessions/a/messages': slow.promise })
  const first = chat.select('a'); await Promise.resolve(); await Promise.resolve()
  assert.deepEqual(chat.state.messages, [])
  await chat.select('b'); slow.resolve([{ id: 'late', content: 'A迟到' }]); await first
  assert.equal(chat.state.sessionId, 'b'); assert.equal(chat.state.messages[0].content, 'B消息')
  assert.equal(chat.selected.value.mode, 'KNOWLEDGE')
})

test('历史轨迹异步查询不覆盖后来选择的run，事件按序号去重', async () => {
  const slow = deferred(); const { chat } = harness({
    '/api/runs/r1/events': slow.promise, '/api/runs/r1/question': { question: null },
    '/api/runs/r2/events': [{ seq: 2 }, { seq: 1 }, { seq: 2 }], '/api/runs/r2/question': { question: null },
  })
  chat.state.runs = [{ id: 'r1' }, { id: 'r2' }]
  const first = chat.viewRun('r1'); await chat.viewRun('r2'); slow.resolve([{ seq: 99 }]); await first
  assert.equal(chat.state.currentRun.id, 'r2'); assert.deepEqual(chat.state.events.map(e => e.seq), [1, 2])
})

test('发送后立即清空输入框并显示用户消息，流未结束也不等刷新', async () => {
  const finish = deferred(); let handlers
  const { chat } = harness({}, async (_m, _s, _t, _c, options) => { handlers = options; await finish.promise; return 'STOP' })
  await chat.init(); chat.state.draft = 'A草稿'; const running = chat.submit()
  // 运行尚未结束：输入框已清空，用户消息已在界面中先行显示。
  assert.equal(chat.state.draft, '')
  assert.equal(chat.state.messages.at(-1).role, 'USER')
  assert.equal(chat.state.messages.at(-1).content, 'A草稿')
  finish.resolve(); await running
  assert.equal(chat.state.draft, '')
})

test('回答期间可继续发送，排队消息不覆盖当前回答，轮到时接管视图', async () => {
  const first = deferred(); const second = deferred(); const started = []; const opts = []
  const { chat } = harness({}, async (message, _s, text, _c, options) => {
    started.push(message); opts.push(options)
    if (message === '第一条') {
      options.onMeta({ runId: 'r1', status: 'RUNNING' }); text('第一段')
      await first.promise; text('完成'); return 'STOP'
    }
    options.onMeta({ runId: 'r2', status: 'QUEUED' }); options.onQueue({ runId: 'r2', status: 'QUEUED' })
    await second.promise; return 'STOP'
  })
  await chat.init(); chat.state.draft = '第一条'; const a = chat.submit()
  chat.state.draft = '第二条'; const b = chat.submit()
  // 两条请求各自订阅；第二条已提交但仍在排队。
  assert.deepEqual(started, ['第一条', '第二条'])
  assert.deepEqual(chat.state.messages.slice(-2).map(m => m.content), ['第一条', '第二条'])
  assert.equal(chat.liveAnswer.value, '第一段')
  assert.equal(chat.state.currentRun.id, 'r1')
  assert.deepEqual(chat.queued.value.map(item => item.text), ['第二条'])
  first.resolve(); await a
  // 第一条结束后，排队消息取得执行槽并接管轨迹视图。
  opts[1].onQueue({ runId: 'r2', status: 'RUNNING' })
  assert.equal(chat.state.currentRun.id, 'r2')
  second.resolve(); await b
})

test('取消排队消息调用对应run，不依赖当前选中运行', async () => {
  const hold = deferred()
  const { chat, calls } = harness({ '/api/runs/r1/cancel': { id: 'r1', status: 'CANCELLED' } }, async (_m, _s, _t, _c, options) => {
    options.onMeta({ runId: 'r1', status: 'QUEUED' }); await hold.promise; return 'CANCELLED'
  })
  await chat.init(); chat.state.draft = '排队'; const running = chat.submit()
  await chat.cancelQueued('r1')
  assert.ok(calls.some(([path, options]) => path === '/api/runs/r1/cancel' && options?.method === 'POST'))
  assert.equal(chat.state.status, '已取消排队消息')
  hold.resolve(); await running
})

test('切换时流仍属原会话，不把文本或事件灌入其他会话，草稿分别保留', async () => {
  const finish = deferred(); let handlers; let write
  const { chat } = harness({}, async (_m, _s, text, _c, options) => { handlers = options; write = text; await finish.promise; return 'STOP' })
  await chat.init(); chat.state.draft = 'A草稿'; const running = chat.submit()
  handlers.onMeta({ runId: 'ra', status: 'RUNNING' }); write('A输出')
  await chat.select('b'); chat.state.draft = 'B草稿'; handlers.onTool({ seq: 1, type: 'TOOL_STARTED' }); write('继续')
  assert.equal(chat.liveAnswer.value, ''); assert.deepEqual(chat.state.events, []); assert.equal(chat.busy.value, false)
  await chat.select('a'); assert.equal(chat.liveAnswer.value, 'A输出继续'); assert.equal(chat.state.draft, '')
  finish.resolve(); await running; await chat.select('b'); assert.equal(chat.state.draft, 'B草稿')
})

test('新会话采用新模式/资料参数，不覆盖服务端已有会话模式', async () => {
  let sent
  const { chat } = harness({ '/api/sessions': options => options ? (sent = JSON.parse(options.body), { id: 'b' }) : sessions })
  await chat.init(); chat.state.newMode = 'KNOWLEDGE'; chat.state.newBases = ['kb']
  assert.equal(chat.selected.value.mode, 'CHAT')
  await chat.create(); assert.deepEqual(sent.knowledgeBaseIds, ['kb']); assert.equal(sent.mode, 'KNOWLEDGE')
  chat.state.newMode = 'CHAT'; await chat.create(); assert.deepEqual(sent.knowledgeBaseIds, [])
})

test('网络不确定时禁止盲目重发；404后沿用原请求id，不产生新身份', async () => {
  const requestIds = []; const { chat, data } = harness({}, async (_m, _s, _t, _c, options) => { requestIds.push(options.clientRequestId); throw new TypeError('网络断开') })
  await chat.init(); chat.state.draft = '需要确认'; await chat.submit()
  assert.equal(chat.state.error.uncertain, true); assert.equal(chat.canSend.value, false)
  const pending = chat.state.pending
  data[`/api/sessions/a/requests/${pending.clientRequestId}`] = new RequestError('未接收', 404)
  await chat.select('b'); await chat.select('a'); assert.equal(chat.state.pending.clientRequestId, pending.clientRequestId)
  await chat.probe(); assert.equal(chat.state.error.uncertain, false); await chat.submit()
  assert.equal(requestIds.length, 2); assert.equal(requestIds[0], requestIds[1])
})

test('重复409可找到原run，确认后不重新订阅或调用模型', async () => {
  let count = 0; const run = { id: 'r', status: 'SUCCEEDED' }
  const { chat, data } = harness({ '/api/sessions/a/runs': [run], '/api/runs/r/events': [], '/api/runs/r/question': { question: null } }, async () => { count++; throw new RequestError('已接收', 409, { runId: 'r', status: 'SUCCEEDED' }) })
  await chat.init(); chat.state.draft = '问题'; await chat.submit()
  assert.equal(chat.state.pending, null)
  await chat.probe(); assert.equal(count, 1); assert.equal(chat.state.currentRun.id, 'r')
})

test('WAITING_USER回答提交原run/question，不调用新的聊天入口', async () => {
  let captured
  const { chat } = harness({}, () => assert.fail('must not send new chat'))
  // 使用独立注入的回答传输，避免测试产生网络。
  const resume = createChatSession({ api: async path => path.endsWith('/messages') || path.endsWith('/runs') || path.endsWith('/knowledge-bases') || path.endsWith('/sessions') ? [] : { question: null },
    answerStream: async (...args) => { captured = args; return 'STOP' }, storage: null })
  resume.state.sessionId = 'a'; resume.state.question = { id: 'q', runId: 'r', status: 'WAITING_USER' }
  await resume.answer('杭州'); assert.equal(captured[0], 'r'); assert.equal(captured[1], 'q'); assert.equal(captured[2], '杭州')
  assert.equal(chat.state.sessionId, '')
})

test('取消失败不伪造CANCELLED，不清掉输入或中断现有流', async () => {
  const { chat } = harness({ '/api/runs/r/cancel': new RequestError('数据库不可用', 503) })
  await chat.init(); chat.state.runs = [{ id: 'r', status: 'QUEUED' }]; chat.state.draft = '保留输入'
  await chat.cancel(); assert.equal(chat.state.runs[0].status, 'QUEUED'); assert.equal(chat.state.draft, '保留输入'); assert.match(chat.state.error.message, /数据库/)
})

test('查询原请求期间切换会话，后续刷新不能把旧run装进新会话', async () => {
  const gate = deferred(); let hold = false; let intercepted = false
  const { chat } = harness({ '/api/sessions': () => {
    if (hold && !intercepted) { intercepted = true; return gate.promise }
    return sessions
  }, '/api/sessions/a/requests/request': { id: 'old', status: 'SUCCEEDED' } })
  await chat.init(); chat.state.pending = { sessionId: 'a', clientRequestId: 'request' }
  hold = true
  const probe = chat.probe(); await Promise.resolve(); await Promise.resolve()
  assert.equal(intercepted, true)
  await chat.select('b'); gate.resolve(sessions); await probe
  assert.equal(chat.state.sessionId, 'b'); assert.equal(chat.state.currentRun, null); assert.equal(chat.state.messages[0].content, 'B消息')
})
