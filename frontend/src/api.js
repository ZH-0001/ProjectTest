import { reactive } from 'vue'
export const session = reactive({ user: null, ready: false })
let token = ''
export class ApiError extends Error {
  constructor(code, message, status) { super(message); this.code = code; this.status = status }
}
export async function refreshCsrf() {
  const res = await fetch('/api/user/csrf', { credentials: 'same-origin' })
  const body = await res.json()
  if (!res.ok) throw new ApiError(body.code, body.message, res.status)
  token = body.data.csrfToken
}
export async function api(path, { method = 'GET', params, body, csrfRetry = true } = {}) {
  const url = new URL('/api' + path, window.location.origin)
  for (const [key, value] of Object.entries(params || {})) if (value !== undefined && value !== null && value !== '') url.searchParams.set(key, String(value))
  if (method !== 'GET' && !token) await refreshCsrf()
  let res
  try { res = await fetch(url, { method, credentials: 'same-origin', headers: method === 'GET' ? {} : { 'Content-Type': 'application/json', 'X-CSRF-Token': token }, body: body === undefined ? undefined : JSON.stringify(body) }) }
  catch { throw new ApiError('NETWORK_ERROR', '网络连接失败，请重试；预约重试将使用原请求标识', 0) }
  let data
  try { data = await res.json() } catch { throw new ApiError('SERVER_ERROR', '服务暂时不可用，请确认后端已启动', res.status) }
  if (!res.ok) {
    if (data.code === 'CSRF_INVALID' && csrfRetry) { await refreshCsrf(); return api(path, { method, params, body, csrfRetry: false }) }
    if (res.status === 401 && data.code !== 'AUTH_FAILED') { session.user = null; token = '' }
    throw new ApiError(data.code, data.message, res.status)
  }
  return data.data
}
export const post = (path, body) => api(path, { method: 'POST', body })
export async function restore() {
  try { session.user = await api('/user/me') } catch (error) { if (error.status !== 401) console.info('Session restoration unavailable') }
  finally { session.ready = true }
}
export const typeLabel = value => ({ WINDOW: '靠窗', AISLE: '过道', MIDDLE: '中间' }[value] || value)
export const statusLabel = value => ({ PENDING: '待开始', IN_PROGRESS: '进行中', ENDED: '已结束', CANCELLED: '已取消' }[value] || value)
export const displayTime = value => value ? value.replace('T', ' ').slice(0, 16) : '—'
export function chinaDate(days = 0) { return new Date(Date.now() + 8 * 3600000 + days * 86400000).toISOString().slice(0, 10) }
