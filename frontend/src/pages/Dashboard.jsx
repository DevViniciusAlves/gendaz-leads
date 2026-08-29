import { useEffect, useState } from 'react'
import { api } from '../api.js'
import { useToast } from '../components/Toast.jsx'
import { formatNumber } from '../format.js'

const METRICS = [
  ['totalLeads', 'Total de Leads'],
  ['newLeads', 'Novos'],
  ['analyzedLeads', 'Analisados'],
  ['messageReadyLeads', 'Mensagens Prontas'],
  ['approvedLeads', 'Aprovados'],
  ['sentLeads', 'Enviados'],
  ['repliedLeads', 'Responderam'],
  ['interestedLeads', 'Interessados'],
  ['convertedLeads', 'Convertidos'],
  ['blockedLeads', 'Bloqueados'],
  ['activeCampaigns', 'Campanhas Ativas']
]

export function Dashboard() {
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const { push } = useToast()

  useEffect(() => {
    let alive = true
    setLoading(true)
    api
      .get('/api/dashboard')
      .then((d) => alive && setData(d))
      .catch((err) => alive && setError(err.message))
      .finally(() => alive && setLoading(false))
    return () => {
      alive = false
    }
  }, [push])

  if (loading) return <div className="loading">Carregando...</div>
  if (error)
    return <div className="error-state">Erro ao carregar dashboard: {error}</div>
  if (!data) return null

  const allZero = METRICS.every(([k]) => !data[k])

  return (
    <div>
      <h1 className="page-title">Dashboard</h1>
      <p className="page-sub">Visao geral da operacao de prospeccao.</p>

      {allZero ? (
        <div className="empty">
          Nenhum dado ainda. Crie uma campanha para comecar a gerar leads.
        </div>
      ) : (
        <div className="metrics-grid">
          {METRICS.map(([key, label]) => (
            <div className="card metric" key={key}>
              <div className="label">{label}</div>
              <div className="value">{formatNumber(data[key])}</div>
            </div>
          ))}
        </div>
      )}

      {data.statusBreakdown && Object.keys(data.statusBreakdown).length > 0 && (
        <>
          <div className="section-title">Leads por status</div>
          <div className="card">
            <div className="dl">
              {Object.entries(data.statusBreakdown).map(([status, count]) => (
                <div key={status} style={{ display: 'contents' }}>
                  <dt>{status}</dt>
                  <dd>{formatNumber(count)}</dd>
                </div>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  )
}
