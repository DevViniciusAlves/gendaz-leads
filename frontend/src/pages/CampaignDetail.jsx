import { useEffect, useState, useRef } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { api } from '../api.js'
import { useToast } from '../components/Toast.jsx'
import { CampaignStatusBadge } from '../components/StatusBadge.jsx'
import { IconRefresh } from '../components/Icons.jsx'
import { formatDate, formatNumber } from '../format.js'

const PROCESSING = ['CREATED', 'DISCOVERING', 'ANALYZING', 'GENERATING']

export function CampaignDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { push } = useToast()
  const [c, setC] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [retrying, setRetrying] = useState(false)
  const timer = useRef(null)

  function load() {
    return api
      .get(`/api/campaigns/${id}`)
      .then((d) => {
        setC(d)
        setError('')
        return d
      })
      .catch((err) => {
        setError(err.message)
        return null
      })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    let alive = true
    async function run() {
      const d = await load()
      if (!alive) return
      if (d && PROCESSING.includes(d.status)) {
        timer.current = setTimeout(run, 4000)
      }
    }
    run()
    return () => {
      alive = false
      if (timer.current) clearTimeout(timer.current)
    }
  }, [id, push])

  async function retry() {
    setRetrying(true)
    try {
      await api.post(`/api/campaigns/${id}/retry`, {})
      push('Campanha reiniciada.', 'success')
      setLoading(true)
      load()
    } catch (err) {
      push(err.message, 'error')
    } finally {
      setRetrying(false)
    }
  }

  if (loading && !c)
    return <div className="loading">Carregando campanha...</div>
  if (error && !c)
    return <div className="error-state">Erro: {error}</div>
  if (!c) return null

  const pct =
    c.progressTotal > 0
      ? Math.round((c.progressCurrent / c.progressTotal) * 100)
      : 0
  const isProcessing = PROCESSING.includes(c.status)

  const counters = [
    ['Descobertos', c.discoveredCount],
    ['Analisados', c.analyzedCount],
    ['Mensagens', c.messageCount],
    ['Aprovados', c.approvedCount],
    ['Enviados', c.sentCount],
    ['Responderam', c.repliedCount],
    ['Interessados', c.interestedCount],
    ['Convertidos', c.convertedCount],
    ['Bloqueados', c.blockedCount]
  ]

  return (
    <div>
      <div className="flex-between">
        <div>
          <button className="btn btn-sm" onClick={() => navigate('/campanhas')}>
            Voltar
          </button>
          <h1 className="page-title" style={{ marginTop: 12 }}>
            {c.name}
          </h1>
          <p className="page-sub">
            {c.niche} · {c.location} · Solicitados:{' '}
            {formatNumber(c.requestedQuantity)}
          </p>
        </div>
        <CampaignStatusBadge status={c.status} />
      </div>

      {(isProcessing || c.progressStage) && (
        <div className="card" style={{ marginBottom: 20 }}>
          <div className="flex-between" style={{ marginBottom: 10 }}>
            <strong>{c.progressStage || 'Processando...'}</strong>
            <span className="muted">
              {formatNumber(c.progressCurrent)} / {formatNumber(c.progressTotal)}
            </span>
          </div>
          <div className="progress">
            <span style={{ width: `${pct}%` }} />
          </div>
        </div>
      )}

      {c.errorMessage && (
        <div className="error-state" style={{ marginBottom: 20 }}>
          Erro: {c.errorMessage}
        </div>
      )}

      <div className="flex-between" style={{ marginBottom: 12 }}>
        <div className="section-title" style={{ margin: 0 }}>
          Contadores
        </div>
        {(c.status === 'FAILED' || c.status === 'PARTIAL') && (
          <button
            className="btn btn-primary btn-sm"
            onClick={retry}
            disabled={retrying}
          >
            <IconRefresh width={14} height={14} />
            {retrying ? 'Reiniciando...' : 'Tentar novamente'}
          </button>
        )}
      </div>

      <div className="metrics-grid">
        {counters.map(([label, val]) => (
          <div className="card metric" key={label}>
            <div className="label">{label}</div>
            <div className="value">{formatNumber(val)}</div>
          </div>
        ))}
      </div>

      <div className="section-title">Leads desta campanha</div>
      <Link className="btn btn-sm" to={`/leads?campaignId=${c.id}`}>
        Ver leads da campanha
      </Link>

      <div className="muted" style={{ marginTop: 20, fontSize: 12 }}>
        Criada em {formatDate(c.createdAt)} · Atualizada em{' '}
        {formatDate(c.updatedAt)}
      </div>
    </div>
  )
}
