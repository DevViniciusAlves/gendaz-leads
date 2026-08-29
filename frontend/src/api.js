// Central fetch wrapper for the Gendaz Leads backend.
// Attaches the JWT from localStorage and surfaces server error messages.

const BASE = (import.meta.env.VITE_API_URL || '').replace(/\/$/, '')

function getToken() {
  return localStorage.getItem('gl_token')
}

function setToken(token) {
  if (token) localStorage.setItem('gl_token', token)
  else localStorage.removeItem('gl_token')
}

async function request(path, options = {}) {
  const url = `${BASE}${path}`
  const headers = { ...(options.headers || {}) }

  const token = getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`

  if (options.body && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
  }

  const res = await fetch(url, {
    method: options.method || 'GET',
    headers,
    body: options.body
      ? options.body instanceof FormData
        ? options.body
        : JSON.stringify(options.body)
      : undefined
  })

  if (res.status === 401) {
    setToken(null)
    const err = new Error('Sessao expirada. Faca login novamente.')
    err.status = 401
    throw err
  }

  const contentType = res.headers.get('content-type') || ''
  let data = null
  if (contentType.includes('application/json')) {
    data = await res.json()
  }

  if (!res.ok) {
    const message =
      (data && (data.message || data.error)) ||
      `Erro ${res.status} ao chamar ${path}`
    const err = new Error(message)
    err.status = res.status
    err.data = data
    throw err
  }

  return data
}

export const api = {
  get: (path) => request(path),
  post: (path, body) => request(path, { method: 'POST', body }),
  patch: (path, body) => request(path, { method: 'PATCH', body }),
  del: (path) => request(path, { method: 'DELETE' })
}

export { getToken, setToken }
