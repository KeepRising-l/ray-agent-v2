let token = ''
let header = 'X-CSRF-TOKEN'
export function csrfHeaders(method = 'GET') {
  return ['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase()) || !token ? {} : { [header]: token }
}
export async function readSession() {
  const response = await fetch('/api/auth/session', { cache: 'no-store' })
  if (!response.ok) throw new Error('无法读取登录状态，请检查后端')
  const session = await response.json(); token = session.csrfToken; header = session.csrfHeader || 'X-CSRF-TOKEN'
  return session
}
export async function login(username, password) {
  await readSession()
  const response = await fetch('/api/auth/login', { method: 'POST', headers: { ...csrfHeaders('POST'), 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ username, password }) })
  if (!response.ok) throw new Error(response.status === 429 ? '登录尝试过多，请稍后重试' : '账号或密码不正确')
  return readSession()
}
export async function logout() {
  const response = await fetch('/api/auth/logout', { method: 'POST', headers: csrfHeaders('POST') })
  if (!response.ok) throw new Error('退出未确认，请刷新页面检查登录状态')
  token = ''
  // 浏览器禁用本地存储时，服务端退出仍然有效；不让清理异常阻止页面返回登录状态。
  try { for (const key of Object.keys(localStorage)) if (key.startsWith('ray-')) localStorage.removeItem(key) } catch { /* 服务端会话已经失效。 */ }
  return readSession()
}
export async function authRejected(status) {
  if (typeof window === 'undefined' || ![401, 403].includes(status)) return
  try {
    const session = await readSession()
    if (!session.authenticated) window.dispatchEvent(new Event('ray-auth-required'))
  } catch { if (status === 401) window.dispatchEvent(new Event('ray-auth-required')) }
}
