import { reactive, computed, shallowReactive } from 'vue'
import { requestJson } from '../api/http.js'
import { streamChat, streamAnswer } from '../api/chat.js'

const ACTIVE = ['QUEUED', 'RUNNING', 'WAITING_USER']
const post = value => ({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) })

/** 页面状态按会话隔离；服务端保存的模式、消息和终态始终是权威。依赖可注入以验证异步竞态。 */
export function createChatSession({ api = requestJson, stream = streamChat, answerStream = streamAnswer, storage = globalThis.localStorage } = {}) {
  const state = reactive({
    sessions: [], bases: [], channels: [], sessionId: '', channelId: '', messages: [], runs: [], events: [],
    currentRun: null, question: null, draft: '', newMode: 'CHAT', newBases: [], title: '',
    fetching: false, creating: false, status: '', error: null, pending: null, partial: null,
  })
  // 一个会话可同时有多条流（运行中 + 排队中）；切换会话只改变视图，原流仍可完成并持久保存。
  const operations = shallowReactive(new Map()) // key: clientRequestId / 问答续跑键
  const drafts = new Map()
  const notices = new Map()
  let selection = 0
  let traceSelection = 0
  let refreshId = 0
  let opSeq = 0
  let disposed = false
  let refreshing = false
  const selected = computed(() => state.sessions.find(s => s.id === state.sessionId))
  const opsOf = sid => [...operations.values()].filter(op => op.sessionId === sid)
  const runningOp = computed(() => opsOf(state.sessionId).find(op => op.view.phase === 'RUNNING'))
  // 有正在运行的取运行中的，否则取队首排队消息，供状态栏和停止按钮使用。
  const active = computed(() => state.runs.find(run => run.status === 'RUNNING')
    || state.runs.find(run => run.status === 'QUEUED') || null)
  const liveAnswer = computed(() => {
    const op = runningOp.value
    if (op && !(op.view.settled && state.messages.some(m => m.role === 'ASSISTANT' && m.runId === op.runId))) return op.view.text
    const fallback = opsOf(state.sessionId).find(o => o.view.text && !state.messages.some(m => m.role === 'ASSISTANT' && m.runId === o.runId))
    if (fallback) return fallback.view.text
    return state.partial && !state.messages.some(m => m.role === 'ASSISTANT' && m.runId === state.partial.runId) ? state.partial.text : ''
  })
  const busy = computed(() => opsOf(state.sessionId).length > 0)
  // 运行中但还没有任何文字时显示思考泡泡；模型开始输出后由 liveAnswer 接管。
  const thinking = computed(() => {
    const op = runningOp.value
    return !!op && !op.view.text && !state.question
  })
  // 排队消息优先取本页仍在读取的流；刷新或从其他标签页进入时回退到服务端 runs。
  const queued = computed(() => {
    const items = opsOf(state.sessionId).filter(op => op.view.phase === 'QUEUED')
      .sort((a, b) => a.order - b.order)
      .map(op => ({ key: op.key, runId: op.runId, text: op.payload?.message || '排队中的消息' }))
    for (const run of state.runs.filter(item => item.status === 'QUEUED').slice().reverse()) {
      if (items.some(item => item.runId === run.id)) continue
      const message = state.messages.find(item => item.role === 'USER' && item.runId === run.id)
      items.push({ key: run.id, runId: run.id, text: message?.content || '排队中的消息' })
    }
    return items.map((item, index) => ({ ...item, position: index + 1 }))
  })
  // 运行中允许继续发送；WAITING_USER 时必须先回答或取消。
  const canSend = computed(() => !state.fetching && !state.creating && !state.question
    && !state.error?.uncertain && !!state.channelId && !!state.draft.trim()
    && (state.sessionId || state.newMode !== 'KNOWLEDGE' || state.newBases.length > 0))
  const save = (key, value) => { try { storage?.setItem(key, value) } catch { /* 浏览器禁用存储时仍能使用本页。 */ } }
  const read = key => { try { return storage?.getItem(key) } catch { return null } }
  const forgetPending = sid => { try { storage?.removeItem?.(`ray-pending-request:${sid}`) } catch { /* 不影响服务端状态。 */ } }
  function clearView() {
    state.messages = []; state.runs = []; state.events = []; state.currentRun = null; state.question = null
  }
  function errorNotice(error) {
    return { message: error.message || '请求失败，请检查连接', uncertain: false }
  }
  function addEvent(event) {
    // SSE和查询可能包含同一序号；只展示一次，并按服务端序号排列。
    if (!state.events.some(item => item.seq === event.seq)) state.events.push(event)
    state.events.sort((a, b) => a.seq - b.seq)
  }
  async function loadRun(run, token = selection) {
    if (disposed || token !== selection) return
    const trace = ++traceSelection
    state.currentRun = run; state.events = []; state.question = null
    if (!run) return
    const [events, question] = await Promise.all([api(`/api/runs/${run.id}/events`), api(`/api/runs/${run.id}/question`)])
    if (disposed || token !== selection || trace !== traceSelection || state.currentRun?.id !== run.id) return
    state.events = [...new Map(events.map(event => [event.seq, event])).values()].sort((a, b) => a.seq - b.seq)
    state.question = question.question
  }
  async function refresh() {
    const token = selection; const sid = state.sessionId; const sequence = ++refreshId
    const [sessions, bases, channels] = await Promise.all([api('/api/sessions'), api('/api/knowledge-bases'), api('/api/model-channels')])
    if (disposed || token !== selection || sequence !== refreshId) return
    state.sessions = sessions; state.bases = bases; state.channels = channels
    if (!channels.some(channel => channel.id === state.channelId)) state.channelId = channels[0]?.id || ''
    if (!sid) return
    const [messages, runs] = await Promise.all([api(`/api/sessions/${sid}/messages`), api(`/api/sessions/${sid}/runs`)])
    if (disposed || token !== selection || sequence !== refreshId) return
    state.messages = messages; state.runs = runs
    // 有正在输出的流时保留其轨迹视图，不能让较早结束的 op 触发的刷新把视图换走。
    const running = runningOp.value
    const selectedRun = (running && runs.find(run => run.id === running.runId))
      || runs.find(run => run.id === state.currentRun?.id) || runs[0] || null
    await loadRun(selectedRun, token)
  }
  async function select(id) {
    drafts.set(state.sessionId, state.draft)
    notices.set(state.sessionId, { error: state.error, pending: state.pending, status: state.status, partial: state.partial })
    ++selection; ++traceSelection; ++refreshId
    const notice = notices.get(id) || {}
    // 刷新页面也保留未确认请求的原编号；只恢复提示，不自动发送或重订阅。
    if (!notice.pending) {
      try {
        const pending = JSON.parse(read(`ray-pending-request:${id}`) || 'null')
        if (pending?.sessionId === id && typeof pending.clientRequestId === 'string' && typeof pending.message === 'string') {
          notice.pending = pending; notice.error = { message: '上次请求结果尚未确认，请查询原请求。', uncertain: true }
        }
      } catch { /* 无效本地记录不作为可执行请求。 */ }
    }
    state.sessionId = id; state.draft = drafts.get(id) || ''; state.status = notice.status || ''
    state.error = notice.error || null; state.pending = notice.pending || null; state.partial = notice.partial || null
    // 运行中切回会话时，消息已提交并显示，不再回填输入框；只有未确认的请求才恢复文字。
    if (!state.draft && notice.pending && opsOf(id).length === 0) state.draft = notice.pending.message
    if (notice.pending?.channelId && state.channels.some(channel => channel.id === notice.pending.channelId)) state.channelId = notice.pending.channelId
    clearView(); state.fetching = true; save('ray-session', id)
    const token = selection
    try { await refresh() } catch (error) { if (token === selection) state.error = errorNotice(error) }
    finally { if (token === selection) state.fetching = false }
    const current = opsOf(id).find(op => op.view.phase === 'RUNNING') || opsOf(id).at(-1)
    if (current) state.status = current.view.status
  }
  async function init() {
    try {
      const [channels, sessions, bases] = await Promise.all([api('/api/model-channels'), api('/api/sessions'), api('/api/knowledge-bases')])
      if (disposed) return
      state.channels = channels; state.channelId = channels[0]?.id || ''; state.sessions = sessions; state.bases = bases
      const saved = read('ray-session')
      await select(sessions.find(s => s.id === saved)?.id || sessions[0]?.id || '')
      if (!state.channelId) state.status = '模型尚未配置，请先配置后端。'
    } catch (error) { state.error = errorNotice(error) }
  }
  async function create() {
    if (state.creating) return false
    state.creating = true
    try {
      const created = await api('/api/sessions', post({ title: state.title.trim() || (state.newMode === 'CHAT' ? '新会话' : `${state.newMode}会话`),
        mode: state.newMode, knowledgeBaseIds: state.newMode === 'CHAT' ? [] : [...state.newBases] }))
      state.title = ''; await select(created.id); state.channelId = state.channels[0]?.id || ''; return true
    } catch (error) { state.error = errorNotice(error); return false }
    finally { state.creating = false }
  }
  async function execute(payload, question = null) {
    const sid = payload.sessionId
    // 每条请求独占一个 operation；同会话的后续消息只排队，不再阻止发送。
    const key = question ? `answer:${question.runId}:${question.id}` : payload.clientRequestId
    if (operations.has(key)) return
    const op = { key, order: ++opSeq, sessionId: sid, controller: new AbortController(),
      runId: question?.runId || '', payload,
      // phase/text 放在 reactive view 中，排队转执行时界面才响应式更新。
      view: reactive({ text: '', status: '正在提交…', settled: false, phase: question ? 'RUNNING' : 'SUBMITTED' }) }
    operations.set(key, op)
    const others = () => [...operations.values()].filter(item => item !== op)
    // newer：已有更晚的请求，旧请求不能覆盖错误、草稿和待确认记录。
    const newer = () => others().some(item => item.order > op.order)
    // leading：当前由这条 operation 输出回答；排队消息不能抢走轨迹视图和状态栏。
    const leading = () => !others().some(item => item.view.phase === 'RUNNING')
    if (!question) {
      notices.set(sid, { pending: payload, status: state.status })
      save(`ray-pending-request:${sid}`, JSON.stringify(payload))
    }
    const visible = () => !disposed && state.sessionId === sid
    const status = text => {
      op.view.status = text
      if (!visible()) return
      const running = opsOf(sid).find(item => item.view.phase === 'RUNNING')
      if (running ? running !== op : newer()) return
      state.status = text
    }
    let terminal = false
    let refused = false
    if (visible() && !newer()) { state.error = null; state.partial = null; state.status = op.view.status }
    // 用户手动查看历史运行时不把新流的事件混入那条轨迹；开始执行后由 adoptView 切回本 run。
    const event = value => {
      if (!visible() || !leading()) return
      if (state.currentRun?.id && state.currentRun.id !== op.runId) return
      addEvent(value)
    }
    // 只有接管回答视图的 operation 才替换轨迹和当前运行，避免排队消息清掉正在显示的回答。
    const adoptView = () => {
      if (!visible() || !leading()) return
      ++traceSelection
      state.events = []
      state.currentRun = { id: op.runId, status: op.view.phase, channelId: payload.channelId }
      state.question = null
    }
    const options = {
      sessionId: sid, clientRequestId: payload.clientRequestId,
      onMeta: meta => {
        op.runId = meta.runId
        op.view.phase = meta.status || 'QUEUED'
        // 本地先行消息按 clientRequestId 绑定 runId；刷新后由服务端消息替换。
        const local = state.messages.find(item => item.clientRequestId === payload.clientRequestId)
        if (local) local.runId = meta.runId
        adoptView()
      },
      onQueue: value => {
        op.view.phase = value.status
        if (visible() && state.currentRun?.id === op.runId) state.currentRun.status = value.status
        if (value.status === 'RUNNING') adoptView()
        status(value.status === 'QUEUED' ? '已排队，等待前一条消息完成…' : '正在执行…')
      },
      onTool: value => { event(value); status(value.type === 'TOOL_STARTED' ? '正在执行工具…' : '正在继续回答…') },
      onRoute: event,
      onContext: value => { event(value); if (value.type === 'COMPACTION_STARTED') status('正在整理较早的对话历史…') },
      onRag: value => { event(value); if (value.type === 'RAG_REFUSED') refused = true },
      onQuestion: value => { terminal = true; op.view.phase = 'WAITING_USER'; if (visible() && leading()) state.question = value },
      onDone: () => { terminal = true },
    }
    try {
      const text = value => { op.view.text += value }
      const result = question
        ? await answerStream(question.runId, question.id, payload.message, op.controller.signal, text, options)
        : await stream(payload.message, op.controller.signal, text, payload.channelId, options)
      terminal = true
      op.view.phase = result === 'WAITING_USER' ? 'WAITING_USER' : 'DONE'
      status(result === 'WAITING_USER' ? '等待你补充信息，运行已暂停' : refused ? '资料依据不足' : '本次运行已结束')
      if (!newer()) {
        // 输入框在提交时已清空；这里不再清，避免覆盖运行期间重新输入的内容。
        if (visible() && state.pending?.clientRequestId === payload.clientRequestId) state.pending = null
        notices.set(sid, { status: op.view.status })
        forgetPending(sid)
      }
    } catch (error) {
      terminal ||= !!error.runStatus || error.status > 0
      const notice = errorNotice(error)
      notice.uncertain = !terminal && error.name !== 'AbortError'
      if (error.runId) op.runId = error.runId
      if (error.name === 'AbortError') notice.message = '连接已关闭，请刷新确认运行状态。'
      status(notice.uncertain ? '请求结果尚未确认' : '本次请求未完成，请查看原因')
      op.view.phase = 'DONE'
      const partial = op.view.text ? { runId: op.runId, text: op.view.text } : null
      // 已有 runId 或明确 HTTP 状态说明服务端已给出结论，不再保留“未确认请求”记录。
      const pending = question || error.runId || error.status > 0 ? null : payload
      if (!newer()) {
        notices.set(sid, { error: notice, pending, partial, status: op.view.status })
        if (terminal) forgetPending(sid)
      }
      if (visible() && !newer()) {
        state.error = notice; state.partial = partial
        state.pending = pending
        if (error.runId) state.currentRun = { id: error.runId }
      }
      // 只有没有更新的请求时，才把失败的问题放回输入框。
      if (!question && !newer()) {
        if (visible() && !state.draft) state.draft = payload.message
        else if (!visible()) drafts.set(sid, payload.message)
      }
    } finally {
      op.view.settled = true
      if (visible()) {
        try { await refresh() } catch { if (visible() && !newer()) { state.error = { message: '无法刷新历史；请查询原请求或刷新状态。', uncertain: !terminal }; if (!question) state.pending = payload } }
        // 已收到meta时可通过run状态确认结果；不因一次刷新就重发模型请求。
        if (visible() && !newer() && state.error?.uncertain && op.runId) {
          const run = state.runs.find(item => item.id === op.runId)
          if (run) { state.error.uncertain = false; state.pending = null; forgetPending(sid) }
        }
      }
      operations.delete(key)
    }
  }
  async function submit() {
    if (!canSend.value) return
    const message = state.draft.trim(); const channelId = state.channelId
    if (!state.sessionId && !await create()) return
    const previous = state.pending
    const clientRequestId = previous && previous.sessionId === state.sessionId && previous.message === message && previous.channelId === channelId
      ? previous.clientRequestId : crypto.randomUUID()
    const payload = { message, channelId, sessionId: state.sessionId, clientRequestId }
    state.pending = payload
    // 发送即清空输入框，并先在本地显示这条用户消息；服务端历史在运行结束后刷新校准。
    state.draft = ''
    drafts.set(state.sessionId, '')
    // 留下请求编号，收到 meta 后绑定 runId；刷新历史时由服务端消息替换。
    state.messages.push({ id: `local-${clientRequestId}`, clientRequestId, runId: null, role: 'USER', content: message, complete: true })
    await execute(payload)
  }
  async function answer(value) {
    if (state.question?.status !== 'WAITING_USER') return
    await execute({ sessionId: state.sessionId, channelId: state.currentRun?.channelId, message: value }, state.question)
  }
  async function cancel() {
    const sid = state.sessionId
    const ops = opsOf(sid)
    const op = ops.find(item => item.view.phase === 'RUNNING') || ops.find(item => item.runId) || ops.at(-1)
    const run = op?.runId || active.value?.id
    if (!run) { state.error = { message: '尚未收到运行编号，请先查询本次请求。', uncertain: true }; return }
    try {
      await api(`/api/runs/${run}/cancel`, { method: 'POST' })
      op?.controller.abort()
      if (sid === state.sessionId) { state.status = '已请求取消'; await refresh() }
    } catch (error) { if (sid === state.sessionId) state.error = errorNotice(error) }
  }
  async function cancelQueued(runId) {
    const sid = state.sessionId
    try {
      await api(`/api/runs/${runId}/cancel`, { method: 'POST' })
      const op = opsOf(sid).find(item => item.runId === runId)
      op?.controller.abort()
      if (sid === state.sessionId) { state.status = '已取消排队消息'; await refresh() }
    } catch (error) { if (sid === state.sessionId) state.error = errorNotice(error) }
  }
  async function probe() {
    const pending = state.pending
    if (!pending) {
      try {
        if (!state.channels.length) { await init(); return }
        await refresh(); state.error = null
      } catch (error) { state.error = errorNotice(error) }
      return
    }
    const token = selection
    try {
      const run = await api(`/api/sessions/${pending.sessionId}/requests/${pending.clientRequestId}`)
      if (token !== selection) return
      state.pending = null; state.error = null; forgetPending(pending.sessionId); await refresh()
      if (token !== selection) return
      await loadRun(run, token)
      state.status = '已找到原运行；没有重新执行模型。'
    } catch (error) {
      if (token !== selection) return
      state.error = { message: error.status === 404 ? '暂未查到运行，再次发送将沿用原请求编号。' : error.message,
        uncertain: error.status !== 404 }
    }
  }
  function editLast() {
    const latest = [...state.messages].reverse().find(item => item.role === 'USER')
    if (latest) state.draft = latest.content
    state.pending = null
    forgetPending(state.sessionId)
    state.status = '问题已放回输入框，修改后点击发送会创建新运行。'
  }
  async function poll() {
    if (refreshing || busy.value || !active.value) return
    refreshing = true
    try { await refresh() } catch (error) { state.error = errorNotice(error) }
    finally { refreshing = false }
  }
  function dispose() {
    disposed = true; ++selection; ++traceSelection
    for (const op of operations.values()) op.controller.abort()
    operations.clear()
  }
  return { state, selected, active, busy, queued, thinking, liveAnswer, canSend, init, select, create, refresh, submit, answer, cancel, cancelQueued, probe, editLast, poll, dispose,
    viewRun: async id => { const token = selection; try { await loadRun(state.runs.find(run => run.id === id) || null) } catch (error) { if (token === selection) state.error = errorNotice(error) } } }
}
