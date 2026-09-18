import { useEffect, useState, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { api } from '../api.js'
import { useToast } from '../components/Toast.jsx'
import { CampaignStatusBadge } from '../components/StatusBadge.jsx'
import { IconRefresh, IconCheck } from '../components/Icons.jsx'
import { formatDate, formatNumber } from '../format.js'
import { Modal } from '../components/Modal.jsx'

const PROCESSING = ['CREATED', 'DISCOVERING', 'ANALYZING', 'GENERATING']

export function CampaignDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { push } = useToast()
  const [c, setC] = useState(null)
  const [leads, setLeads] = useState([])
  const [selectedLeads, setSelectedLeads] = useState(new Set())
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [retrying, setRetrying] = useState(false)
  const timer = useRef(null)

  // Preview Modal state
  const [showPreview, setShowPreview] = useState(false)
  const [previewData, setPreviewData] = useState(null)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [sending, setSending] = useState(false)

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

  function loadLeads() {
    api.get(`/api/campaigns/${id}/messaging-leads`)
      .then(d => {
        setLeads(d || [])
      })
      .catch(() => {})
  }

  useEffect(() => {
    let alive = true
    async function run() {
      const d = await load()
      if (!alive) return
      if (d && PROCESSING.includes(d.status)) {
        timer.current = setTimeout(run, 4000)
      } else {
        loadLeads()
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

  function toggleLead(leadId) {
    const next = new Set(selectedLeads)
    if (next.has(leadId)) next.delete(leadId)
    else next.add(leadId)
    setSelectedLeads(next)
  }

  function toggleCheckbox(leadId, eligible) {
    if (!eligible) return;
    const next = new Set(selectedLeads)
    if (next.has(leadId)) next.delete(leadId)
    else next.add(leadId)
    setSelectedLeads(next)
  }

  async function handlePreview() {
    if (selectedLeads.size === 0) {
      push('Selecione pelo menos um lead.', 'error')
      return
    }
    setPreviewLoading(true)
    setShowPreview(true)
    try {
      const res = await api.post(`/api/campaigns/${id}/send-preview`, {
        leadIds: Array.from(selectedLeads)
      })
      setPreviewData(res)
    } catch (err) {
      push(err.message, 'error')
      setShowPreview(false)
    } finally {
      setPreviewLoading(false)
    }
  }

  async function handleSendAllEligible() {
    setPreviewLoading(true)
    setShowPreview(true)
    try {
      const res = await api.post(`/api/campaigns/${id}/send-preview`, {
        allEligible: true
      })
      setPreviewData(res)
      setShowPreview(true)
    } catch (err) {
      push(err.message, 'error')
      setShowPreview(false)
    } finally {
      setPreviewLoading(false)
    }
  }

  async function confirmSend() {
    setSending(true)
    try {
      await api.post(`/api/campaigns/${id}/send`, {
        leadIds: Array.from(selectedLeads),
        allEligible: false
      })
      push('Mensagens enviadas para a fila.', 'success')
      setShowPreview(false)
      setSelectedLeads(new Set())
      loadLeads()
      load()
    } catch (err) {
      push(err.message, 'error')
    } finally {
      setSending(false)
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

  const eligibleCount = leads.filter(l => l.eligible).length

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

      <div className="section-title" style={{ marginTop: 24, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span>Leads desta campanha</span>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn btn-sm" onClick={selectAllEligible}>
            Selecionar Todos Elegiveis ({eligibleCount})
          </button>
          <button className="btn btn-secondary btn-sm" onClick={handleSendAllEligible} disabled={eligibleCount === 0}>
            Enviar para todos elegíveis ({eligibleCount})
          </button>
          <button className="btn btn-primary btn-sm" onClick={handlePreview} disabled={selectedLeads.size === 0}>
            Preview & Enviar ({selectedLeads.size})
          </button>
        </div>
      </div>

      {leads.length > 0 ? (
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th style={{ width: 40 }}>Sel</th>
                <th>Empresa</th>
                <th>Contato</th>
                <th>Instagram</th>
                <th>Local</th>
                <th>Site</th>
                <th>Score</th>
                <th>Sistema</th>
                <th>Envio</th>
              </tr>
            </thead>
            <tbody>
              {leads.map(l => {
                const isEligible = l.eligible
                return (
                  <tr key={l.leadId} className={isEligible ? 'clickable' : ''} onClick={() => isEligible && toggleLead(l.leadId)}>
                    <td>
                      <input
                        type="checkbox"
                        checked={selectedLeads.has(l.leadId)}
                        onChange={() => toggleCheckbox(l.leadId, isEligible)}
                        disabled={!isEligible}
                        onClick={(e) => e.stopPropagation()}
                      />
                    </td>
                    <td>
                      <strong>{l.businessName}</strong>
                    </td>
                    <td>
                      {l.phone !== undefined && l.phone !== null ? l.phone : '-'}

                      {l.ineligibilityCode === 'NO_PHONE' && (
                        <span className="muted" style={{ fontSize: 12, color: 'red' }}>Sem telefone</span>
                      )}
                    </td>
                    <td>
                      {l.instagramUsername != null ? '@' + l.instagramUsername : '-'}
                    </td>
                    <td>
                      {l.city != null || l.state != null ? (
                        <div>
                          {l.city != null ? l.city : ''}{' '}
                          {l.state != null ? l.state : ''}
                        </div>
                      ) : '-'}

                      {l.ineligibilityCode === 'DO_NOT_CONTACT' && (
                        <span className="muted" style={{ fontSize: 12, color: 'red' }}>Não prospectar</span>
                      )}
                    </td>
                    <td>{l.opportunityScore != null ? String(l.opportunityScore) : '-'}</td>
                    <td>{l.detectedSystem != null ? l.detectedSystem : 'Não identificado'}</td>
                    <td>
                      {l.sendStatus !== null && l.sendStatus !== undefined ? (
                        <span className={"badge ".concat(getBadgeClass(l.sendStatus))}>
                          {l.sendStatus}
                        </span>
                      ) : (
                        <span className="badge badge-gray">Pronto</span>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="empty">Nenhum lead encontrado ainda.</div>
      )}

      {showPreview && (
        <Modal
          title="Revisar envio"
          onClose={() => setShowPreview(false)}
          footer={
            <>
              <button className="btn" onClick={() => setShowPreview(false)} disabled={sending}>Cancelar</button>
              <button className="btn btn-primary" onClick={confirmSend} disabled={sending || previewLoading}>
                {sending ? 'Enviando...' : 'Confirmar Envio'}
              </button>
            </>
          }
        >
          {previewLoading ? (
            <div className="loading">Gerando preview...</div>
          ) : previewData ? (
            <div>
              <p>{previewData.eligibleCount} mensagens serão adicionadas à fila.</p>
              {previewData.ineligibleCount > 0 && (
                <p>{previewData.ineligibleCount} lead será{'s' .previewData.ineligibleCount > 1 ? '' : ''} ignorado.</p>
              )}
              <div style={{ marginTop: 16, maxHeight: 300, overflowY: 'auto' }}>
                <strong>Pré-visualização:</strong>
                <div>
                  {previewData.previews.map((p, idx) => (
                    <div key={idx} style={{ marginBottom: 12, whiteSpace: 'pre-wrap' }}>
                      <strong>{p.businessName}</strong>: {p.message}
                    </div>
                  ))}
                </div>
              </div>
              {previewData.ineligible.length > 0 && (
                <div style={{ marginTop: 16, marginBottom: 12 }}>
                  <strong>Ignorados:</strong>
                  <div>
                    {previewData.ineligible.map((i, idx) => (
                      <div key={idx} style={{ marginBottom: 6, fontSize: 14 }}>
                        {i.businessName} — {i.reason}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          ) : null}
        </Modal>
      )}

      <div className="muted" style={{ marginTop: 20, fontSize: 12 }}>
        Criada em {formatDate(c.createdAt)} · Atualizada em{' '}
        {formatDate(c.updatedAt)}
      </div>
    </div>
  )
}

function getBadgeClass(status) {
  switch (status) {
    case 'QUEUED': return 'badge-blue';
    case 'SENDING': return 'badge-orange';
    case 'SENT': return 'badge-green';
    case 'FAILED': return 'badge-red';
    case 'SKIPPED': return 'badge-gray';
    case 'DELIVERY_UNKNOWN': return 'badge-orange';
    default: return 'badge-gray';
  }
}

function selectAllEligible() {
  const eligibleIds = leads.filter(l => l.eligible).map(l => l.leadId)
  if (selectedLeads.size === eligibleIds.length) {
    setSelectedLeads(new Set())
  } else {
    setSelectedLeads(new Set(eligibleIds))
  }
}