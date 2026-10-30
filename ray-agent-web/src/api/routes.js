/** 页面只显示代码决定的目标和原因码，不展示模型内部思考。刷新时从同一组持久事件恢复。 */
export function routeFromEvents(events = []) {
  const event = [...events].reverse().find(item => item.type === 'ROUTE_DECIDED')
  if (!event) return null
  try {
    const data = JSON.parse(event.detail)
    if (!['DIRECT', 'KNOWLEDGE', 'WEB', 'HYBRID', 'CLARIFY'].includes(data.target)) return null
    return data
  } catch { return null }
}
