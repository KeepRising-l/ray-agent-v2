const fs = require('node:fs')
async function loginForVerification(page, base) {
  const file = process.env.RAY_TEST_AUTH_FILE
  if (!file) return
  const credentials = Object.fromEntries(fs.readFileSync(file, 'utf8').trim().split(/\r?\n/).map(line => { const split = line.indexOf('='); return [line.slice(0, split), line.slice(split + 1)] }))
  // Vite与代理由父脚本异步启动。等真实会话端点就绪，避免机器忙时抢先登录导致ECONNREFUSED。
  let state
  const deadline = Date.now()+30000
  while (Date.now()<deadline) {
    try {
      const response = await page.request.get(base+'/api/auth/session', {timeout:2000})
      if (response.ok()) {
        const candidate = await response.json()
        if (candidate.runtimeReady && candidate.csrfToken) { state=candidate;break }
      }
    } catch { /* 仅对尚未就绪的读取重试；认证写请求不重放。 */ }
    await new Promise(resolve=>setTimeout(resolve,150))
  }
  if (!state) throw new Error('验证环境会话端点在30秒内未就绪')
  const response = await page.request.post(base+'/api/auth/login', { form: credentials, headers: { [state.csrfHeader]: state.csrfToken } })
  if (!response.ok()) throw new Error('验证环境登录失败，未输出口令')
}
module.exports = { loginForVerification }
