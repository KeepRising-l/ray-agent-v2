/** 保留HTTP状态与原run定位信息；界面按错误范围提供操作，不从错误里自动重跑任务。 */
export class RequestError extends Error {
  constructor(message, status = 0, payload = {}) {
    super(message)
    this.status = status
    this.runId = payload.runId
    this.runStatus = payload.status
    this.sameInput = payload.sameInput
  }
}

export async function requestJson(path, options) {
  const response = await fetch(path, options)
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}))
    throw new RequestError(payload.message || `操作失败（HTTP ${response.status}）`, response.status, payload)
  }
  return response.json()
}
