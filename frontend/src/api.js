// Central fetch wrapper for the Gendaz Leads backend.
// Attaches the JWT from localStorage and surfaces server error messages.

import { clearSession } from './auth.js'

const BASE = (import.meta.env.VITE_API_URL || '').replace(/\/$/, '')

function getToken() {
  return localStorage.getItem('gl_token')
}

function setToken(token) {
  if (token) localStorage.setItem('gl_token', token)
  else localStorage.removeItem('gl_token')
}

async function readJsonSafe(res) {
  const contentType = res.headers.get('content-type') || ''
  if (!contentType.includes('application/json')) return null
  try {
    return await res.json()
  } catch {
    return null
  }
}

function isAuthEndpoint(path) {
  return path === '/api/auth/login' || path === '/api/auth/register'
}

async function request(path, options = {}) {
  const url = `${BASE}${path}`
  const headers = { ...(options.headers || {}) }

  const token = getToken()
  const hadToken = !!token
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

  // Ler o body JSON PRIMEIRO — nunca classificar 401 antes de inspecionar o corpo.
  const data = await readJsonSafe(res)

  if (res.status === 401) {
    const code = data && (data.code || data.error)
    const serverMessage = data && (data.message || data.error)
    // Login com credenciais inválidas: preservar mensagem do backend, NÃO limpar como "sessão expirada".
    if (path === '/api/auth/login' || (isAuthEndpoint(path) && code === 'INVALID_CREDENTIALS')) {
      const err = new Error(serverMessage || 'E-mail ou senha inválidos.')
      err.status = 401
      err.code = code || 'INVALID_CREDENTIALS'
      err.data = data
      throw err
    }
    // Request protegida com token: sessão expirada/inválida.
    if (hadToken && !isAuthEndpoint(path)) {
      clearSession()
      const err = new Error('Sessão expirada. Faça login novamente.')
      err.status = 401
      err.code = code || 'UNAUTHENTICATED'
      err.data = data
      throw err
    }
    // 401 em endpoint público sem token: repassar mensagem do servidor.
    const err = new Error(serverMessage || `Erro ${res.status} ao chamar ${path}`)
    err.status = 401
    err.code = code
    err.data = data
    throw err
  }

  if (!res.ok) {
    const message =
      (data && (data.message || data.error)) ||
      `Erro ${res.status} ao chamar ${path}`
    const err = new Error(message)
    err.status = res.status
    err.code = data && (data.code || data.error)
    err.data = data
    throw err
  }

  return data
}

export const api = {
  get: (path) => request(path),
  post: (path, body) => request(path, { method: 'POST', body }),
  put: (path, body) => request(path, { method: 'PUT', body }),
  patch: (path, body) => request(path, { method: 'PATCH', body }),
  del: (path) => request(path, { method: 'DELETE' })
}

export { getToken, setToken }
