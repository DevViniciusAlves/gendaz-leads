import { Navigate } from 'react-router-dom'
import { getToken } from '../api.js'
import { getUser } from '../auth.js'

export function ProtectedRoute({ children, requiredRole }) {
  const token = getToken()

  if (!token) {
    return <Navigate to="/login" replace />
  }

  if (requiredRole) {
    const user = getUser()

    if (!user || user.role !== requiredRole) {
      return <Navigate to="/dashboard" replace />
    }
  }

  return children
}
