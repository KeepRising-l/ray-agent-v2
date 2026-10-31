import { RequestError } from './http.js'

/** 发送一条消息，逐段读取后端的 text、done、error 事件。 */
export async function streamChat(message, signal, onText, channelId, options = {}) {
  const resume = options.resume
  const response = await fetch(resume ? `/api/runs/${encodeURIComponent(resume.runId)}/answers` : '/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(resume ? { questionId: resume.questionId, answer: resume.answer }
      : { message, channelId, sessionId: options.sessionId, clientRequestId: options.clientRequestId }),
    signal,
  })

  if (!response.ok) {
    const payload = await response.json().catch(() => ({}))
    if (response.status === 429) throw new RequestError('运行容量已满，请等待或取消现有运行后重试。', 429)
    const hint = response.status === 409 ? '当前会话已有运行或请求重复，请查看运行记录。' : `请求失败（HTTP ${response.status}）。`
    throw new RequestError(response.status === 503 ? '模型尚未配置，请先配置后端。' : payload.message || hint, response.status, payload)
  }
  if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) {
    throw new Error('服务没有返回有效的流式响应。')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let finished = false
  let reason = ''

  function handleEvent(block) {
    let name = 'message'
    const data = []
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith('event:')) name = line.slice(6).trim()
      if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
    }
    if (!data.length) return
    const payload = JSON.parse(data.join('\n'))
    if (name === 'meta') options.onMeta?.(payload)
    if (name === 'queue') options.onQueue?.(payload)
    if (name === 'tool') options.onTool?.(payload)
    if (name === 'rag') options.onRag?.(payload)
    if (name === 'route') options.onRoute?.(payload)
    if (name === 'context') options.onContext?.(payload)
    if (name === 'question') {
      options.onQuestion?.(payload)
      finished = true
      reason = 'WAITING_USER'
    }
    if (name === 'text' && typeof payload.text === 'string') onText(payload.text)
    if (name === 'error') throw new RequestError(payload.message || '模型调用失败。', 0, payload)
    if (name === 'done') {
      finished = true
      reason = payload.reason
      options.onDone?.(payload)
    }
  }

  try {
    while (true) {
      const part = await reader.read()
      buffer += decoder.decode(part.value, { stream: !part.done })
      let delimiter
      while ((delimiter = /\r?\n\r?\n/.exec(buffer))) {
        handleEvent(buffer.slice(0, delimiter.index))
        buffer = buffer.slice(delimiter.index + delimiter[0].length)
      }
      if (part.done) break
    }
    if (!finished) throw new Error('连接已结束，但未收到完成事件。')
    return reason
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

/** 补充回答接续同一个run，重复questionId由后端拒绝。 */
export function streamAnswer(runId, questionId, answer, signal, onText, options = {}) {
  return streamChat(null, signal, onText, null, { ...options, resume: { runId, questionId, answer } })
}
