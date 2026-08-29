// User session storage helpers (token handled in api.js).

export function saveUser(user) {
  if (!user) return
  localStorage.setItem(
    'gl_user',
    JSON.stringify({
      userId: user.userId,
      email: user.email,
      fullName: user.fullName,
      role: user.role
    })
  )
}

export function getUser() {
  try {
    const raw = localStorage.getItem('gl_user')
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export function clearSession() {
  localStorage.removeItem('gl_token')
  localStorage.removeItem('gl_user')
}
