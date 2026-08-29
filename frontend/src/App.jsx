import { useEffect, useState } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { api, getToken, setToken } from './api.js'
import { clearSession, saveUser } from './auth.js'
import { ToastProvider } from './components/Toast.jsx'
import { ProtectedRoute } from './components/ProtectedRoute.jsx'
import { AppShell } from './components/AppShell.jsx'
import { Login } from './pages/Login.jsx'
import { Dashboard } from './pages/Dashboard.jsx'
import { Campaigns } from './pages/Campaigns.jsx'
import { CampaignDetail } from './pages/CampaignDetail.jsx'
import { Leads } from './pages/Leads.jsx'
import { LeadDetail } from './pages/LeadDetail.jsx'

function Verifier({ children }) {
  const [checked, setChecked] = useState(false)

  useEffect(() => {
    const token = getToken()
    if (!token) {
      setChecked(true)
      return
    }
    api
      .get('/api/auth/me')
      .then((d) => {
        if (d && d.email) saveUser(d)
        setChecked(true)
      })
      .catch(() => {
        clearSession()
        setChecked(true)
      })
  }, [])

  if (!checked) return <div className="loading">Verificando sessao...</div>
  return children
}

export default function App() {
  return (
    <ToastProvider>
      <BrowserRouter>
        <Verifier>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route
              path="/dashboard"
              element={
                <ProtectedRoute>
                  <AppShell>
                    <Dashboard />
                  </AppShell>
                </ProtectedRoute>
              }
            />
            <Route
              path="/campanhas"
              element={
                <ProtectedRoute>
                  <AppShell>
                    <Campaigns />
                  </AppShell>
                </ProtectedRoute>
              }
            />
            <Route
              path="/campanhas/:id"
              element={
                <ProtectedRoute>
                  <AppShell>
                    <CampaignDetail />
                  </AppShell>
                </ProtectedRoute>
              }
            />
            <Route
              path="/leads"
              element={
                <ProtectedRoute>
                  <AppShell>
                    <Leads />
                  </AppShell>
                </ProtectedRoute>
              }
            />
            <Route
              path="/leads/:id"
              element={
                <ProtectedRoute>
                  <AppShell>
                    <LeadDetail />
                  </AppShell>
                </ProtectedRoute>
              }
            />
            <Route path="*" element={<Navigate to="/dashboard" replace />} />
          </Routes>
        </Verifier>
      </BrowserRouter>
    </ToastProvider>
  )
}
