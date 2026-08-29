import { NavLink, useNavigate } from 'react-router-dom'
import { getUser, clearSession } from '../auth.js'
import { IconDashboard, IconCampaigns, IconLeads, IconLogout } from './Icons.jsx'

export function AppShell({ children }) {
  const user = getUser()
  const navigate = useNavigate()

  function logout() {
    clearSession()
    navigate('/login', { replace: true })
  }

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          Gendaz <span className="accent">Leads</span>
        </div>
        <NavLink
          to="/dashboard"
          className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}
        >
          <IconDashboard />
          Dashboard
        </NavLink>
        <NavLink
          to="/campanhas"
          className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}
        >
          <IconCampaigns />
          Campanhas
        </NavLink>
        <NavLink
          to="/leads"
          className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}
        >
          <IconLeads />
          Leads
        </NavLink>
      </aside>
      <div className="main">
        <header className="topbar">
          <div className="brand">
            Gendaz <span className="accent">Leads</span>
          </div>
          <div className="user">
            <span>
              {user?.fullName || user?.email || 'Usuario'}
              {user?.role ? ` (${user.role})` : ''}
            </span>
            <button className="btn btn-sm" onClick={logout}>
              <IconLogout width={15} height={15} />
              Sair
            </button>
          </div>
        </header>
        <div className="content">{children}</div>
      </div>
    </div>
  )
}
