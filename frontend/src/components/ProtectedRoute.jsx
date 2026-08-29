import { Navigate } from 'react-router-dom'
import { getToken } from '../api.js'

export function ProtectedRoute({ children }) {
  const token = getToken()
  if (!token) return <Navigate to="/login" replace />
  return children
}
