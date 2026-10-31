const labels = {
  QUEUED: '已排队', EXECUTION_STARTED: '开始执行', STARTED: '开始运行', SUCCEEDED: '运行成功',
  FAILED: '运行失败', CANCELLED: '已取消', TIMED_OUT: '已超时', INTERRUPTED: '运行中断',
  WAITING_USER: '等待补充', USER_ANSWERED: '收到补充', MODEL_STARTED: '调用模型', MODEL_FINISHED: '模型返回',
  TOOL_STARTED: '执行工具', TOOL_SUCCEEDED: '工具成功', TOOL_FAILED: '工具失败',
  COMPACTION_STARTED: '整理旧历史', COMPACTION_APPLIED: '已应用摘要', COMPACTION_SKIPPED: '保留原历史',
  COMPACTION_FAILED: '摘要未应用', ROUTE_DECIDED: '已选择回答路径', RAG_RETRIEVAL_STARTED: '检索资料',
  RAG_REFUSED: '资料依据不足', RAG_VALIDATED: '引用校验完成', CONTEXT_PROJECTED: '历史已恢复',
}
/** 展示持久事件而非模拟进度；不展示原始JSON或供应商堆栈。 */
export function timeline(events = []) {
  return [...new Map(events.map(event => [event.seq, event])).values()].sort((a, b) => a.seq - b.seq).map(event => {
    let data = {}
    try {
      const parsed = JSON.parse(event.detail)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) data = parsed
    } catch { /* 终态可能是脱敏纯文本。 */ }
    const description = data.beforeTokens != null ? `本地估算 ${data.beforeTokens} → ${data.afterTokens}`
      : data.target ? `${data.target} · ${data.reason || ''}` : data.name ? `${data.name} ${data.code || ''}`
      : event.type === 'RAG_EVIDENCE' ? `检索到 ${data.citations?.length || 0} 个候选片段` : data.reason || ''
    return { ...event, label: labels[event.type] || event.type, description }
  })
}
