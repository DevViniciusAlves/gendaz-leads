import { useEffect, useState, useCallback } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../api.js'
import { useToast } from '../components/Toast.jsx'
import { LeadStatusBadge } from '../components/StatusBadge.jsx'

const LEAD_STATUSES = [
  'NEW',
  'ANALYZING',
  'ANALYZED',
  'MESSAGE_READY',
  'APPROVED',
  'SENT',
  'REPLIED',
  'INTERESTED',
  'SCHEDULED',
  'CONVERTED',
  'NOT_INTERESTED',
  'DO_NOT_CONTACT',
  'ERROR'
]

export function Leads() {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const { push } = useToast()

  const [items, setItems] = useState([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const [campaignId, setCampaignId] = useState(params.get('campaignId') || '')
  const [status, setStatus] = useState(params.get('status') || '')
  const [search, setSearch] = useState(params.get('search') || '')
  const [campaigns, setCampaigns] = useState([])

  useEffect(() => {
    api
      .get('/api/campaigns?page=0&size=100')
      .then((d) => setCampaigns(d.content || []))
      .catch(() => {})
  }, [push])

  const buildQuery = useCallback(() => {
    const q = new URLSearchParams()
    q.set('page', '0')
    q.set('size', '20')
    if (campaignId) q.set('campaignId', campaignId)
    if (status) q.set('status', status)
    if (search.trim()) q.set('search', search.trim())
    return q.toString()
  }, [campaignId, status, search])

  const load = useCallback(() => {
    setLoading(true)
    api
      .get(`/api/leads?${buildQuery()}`)
      .then((d) => {
        setItems(d.content || [])
        setTotal(d.totalElements || 0)
        setError('')
      })
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false))
  }, [buildQuery, push])

  useEffect(() => {
    load()
  }, [load])

  function applyFilter(key, value) {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    setParams(next, { replace: true })
  }

  function onSearchSubmit(e) {
    e.preventDefault()
    applyFilter('search', search.trim())
  }

  return (
    <div>
      <h1 className="page-title">Leads</h1>
      <p className="page-sub">{total ? `${total} lead(s) encontrado(s).` : 'Filtre para encontrar leads.'}</p>

      <div className="card" style={{ marginBottom: 18 }}>
        <div className="row">
          <div className="field" style={{ margin: 0, minWidth: 220 }}>
            <label>Campanha</label>
            <select
              value={campaignId}
              onChange={(e) => {
                setCampaignId(e.target.value)
                applyFilter('campaignId', e.target.value)
              }}
            >
              <option value="">Todas</option>
              {campaigns.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>
          <div className="field" style={{ margin: 0, minWidth: 200 }}>
            <label>Status</label>
            <select
              value={status}
              onChange={(e) => {
                setStatus(e.target.value)
                applyFilter('status', e.target.value)
              }}
            >
              <option value="">Todos</option>
              {LEAD_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>
          <form onSubmit={onSearchSubmit} className="row" style={{ flex: 1 }}>
            <div className="field" style={{ margin: 0, flex: 1 }}>
              <label>Busca</label>
              <input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Nome, cidade, telefone..."
              />
            </div>
            <button className="btn" type="submit" style={{ marginTop: 22 }}>
              Filtrar
            </button>
            <button
              className="btn"
              type="button"
              style={{ marginTop: 22 }}
              onClick={() => {
                setCampaignId('')
                setStatus('')
                setSearch('')
                setParams({}, { replace: true })
                load()
              }}
            >
              Limpar
            </button>
          </form>
        </div>
      </div>

      {loading && <div className="loading">Carregando...</div>}
      {error && <div className="error-state">Erro: {error}</div>}

      {!loading && !error && items.length === 0 && (
        <div className="empty">Nenhum lead encontrado com os filtros atuais.</div>
      )}

      {!loading && items.length > 0 && (
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Empresa</th>
                <th>Instagram</th>
                <th>Local</th>
                <th>Sistema atual</th>
                <th>Score</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {items.map((l) => (
                <tr
                  key={l.id}
                  className="clickable"
                  onClick={() => navigate(`/leads/${l.id}`)}
                >
                  <td>
                    <strong>{l.businessName}</strong>
                    <div className="muted" style={{ fontSize: 12 }}>
                      {l.category || ''}
                    </div>
                  </td>
                  <td>{l.instagramUsername || '—'}</td>
                  <td>
                    {[l.city, l.state].filter(Boolean).join('/') || '—'}
                  </td>
                  <td>
                    {l.analysis?.detectedSystem ||
                      l.analysis?.bookingSystemStatus ||
                      '—'}
                  </td>
                  <td>
                    {l.analysis?.opportunityScore != null
                      ? l.analysis.opportunityScore
                      : '—'}
                  </td>
                  <td>
                    <LeadStatusBadge status={l.status} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
