/** 根据真实事件还原卡片；只有成功事件携带的网页才显示为来源。 */
export function toolTrace(events, runStatus) {
  const calls = new Map()
  for (const event of events) {
    if (!event.type?.startsWith('TOOL_')) continue
    let data
    try { data = JSON.parse(event.detail) } catch { continue }
    if (!data.callId) continue
    if (event.type === 'TOOL_STARTED') {
      calls.set(data.callId, { ...data, status: 'RUNNING', sources: [] })
    } else if (calls.has(data.callId)) {
      const sources = event.type === 'TOOL_SUCCEEDED' ? (data.sources || []).filter(source => {
        try {
          const url = new URL(source.url)
          return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password
        } catch { return false }
      }) : []
      calls.set(data.callId, { ...data, status: event.type === 'TOOL_SUCCEEDED' ? 'SUCCEEDED' : 'FAILED', sources })
    }
  }
  return [...calls.values()].map(call => call.status === 'RUNNING' && runStatus && runStatus !== 'RUNNING'
    ? { ...call, status: 'INTERRUPTED', code: runStatus } : call)
}
