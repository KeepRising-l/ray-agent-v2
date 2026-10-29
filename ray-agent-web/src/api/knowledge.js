export async function knowledgeRequest(path = '', options) {
  const response = await fetch('/api/knowledge-bases' + path, options)
  if (!response.ok) {
    const error = await response.json().catch(() => ({}))
    throw new Error(error.message || `知识库操作失败（HTTP ${response.status}）`)
  }
  return response.json()
}

export const documentStates = {
  UPLOADED: '待处理', PARSING: '解析中', CHUNKING: '分块中', EMBEDDING: '生成向量中',
  READY: '入库就绪', FAILED: '处理失败', INTERRUPTED: '处理已中断',
}

export function isProcessing(status) {
  return ['UPLOADED', 'PARSING', 'CHUNKING', 'EMBEDDING'].includes(status)
}
