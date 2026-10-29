/** 发送一条消息，逐段读取后端的 text、done、error 事件。 */
export async function streamChat(message, signal, onText, channelId, options = {}) {
  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ message, channelId, sessionId: options.sessionId, clientRequestId: options.clientRequestId }),
    signal,
  })

  if (!response.ok) {
    const hint = response.status === 409 ? '当前会话已有运行或请求重复，请查看运行记录。' : `请求失败（HTTP ${response.status}）。`
    throw new Error(response.status === 503 ? '模型尚未配置，请先配置后端。' : hint)
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
    if (name === 'tool') options.onTool?.(payload)
    if (name === 'text' && typeof payload.text === 'string') onText(payload.text)
    if (name === 'error') throw new Error(payload.message || '模型调用失败。')
    if (name === 'done') {
      finished = true
      reason = payload.reason
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
