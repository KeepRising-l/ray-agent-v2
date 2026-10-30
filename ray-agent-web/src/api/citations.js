/** 从持久事件还原候选和实际使用的引用；原文必须向后端重新校验后读取。 */
export function citationsFromEvents(events) {
  let citations = []
  let used = []
  let refused = false
  for (const event of events) {
    if (!event.type?.startsWith('RAG_')) continue
    let data
    try { data = JSON.parse(event.detail) } catch { continue }
    if (event.type === 'RAG_EVIDENCE') citations = data.citations || []
    if (event.type === 'RAG_VALIDATED') used = data.sourceIds || []
    if (event.type === 'RAG_REFUSED') refused = true
  }
  return { citations, used, refused }
}
