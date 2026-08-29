import { useEffect, useState, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { api } from '../api.js'
import { useToast } from '../components/Toast.jsx'
import { LeadStatusBadge } from '../components/StatusBadge.jsx'
import { IconCopy, IconExternal, IconRefresh } from '../components/Icons.jsx'
import { formatDate } from '../format.js'

const STATUS_OPTIONS = [
  'REPLIED',
  'INTERESTED',
  'CONVERTED',
  'NOT_INTERESTED'
]

export function LeadDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { push } = useToast()

  const [lead, setLead] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [editing, setEditing] = useState(false)
  const [msgDraft, setMsgDraft] = useState('')
  const [saving, setSaving] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    api
      .get(`/api/leads/${id}`)
      .then((d) => {
        setLead(d)
        setMsgDraft(d.message?.messageText || '')
        setError('')
      })
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false))
  }, [id, push])

  useEffect(() => {
    load()
  }, [load])

  async function patch(path, body, successMsg) {
    try {
      const updated = await api.patch(path, body)
      setLead(updated)
      setMsgDraft(updated.message?.messageText || '')
      push(successMsg, 'success')
      return updated
    } catch (err) {
      push(err.message, 'error')
    }
  }

  async function post(path, body, successMsg) {
    try {
      const updated = await api.post(path, body)
      setLead(updated)
      push(successMsg, 'success')
      return updated
    } catch (err) {
      push(err.message, 'error')
    }
  }

  async function saveMessage() {
    setSaving(true)
    await patch(`/api/leads/${id}/message`, { messageText: msgDraft }, 'Mensagem salva.')
    setSaving(false)
    setEditing(false)
  }

  async function approve() {
    await post(`/api/leads/${id}/approve`, {}, 'Lead aprovado.')
  }

  async function doNotContact() {
    if (!window.confirm('Marcar este lead como nao prospectar?')) return
    await post(`/api/leads/${id}/do-not-contact`, {}, 'Marcado como nao prospectar.')
  }

  async function changeStatus(e) {
    const status = e.target.value
    if (!status) return
    await post(`/api/leads/${id}/status`, { status }, `Status atualizado: ${status}.`)
    e.target.value = ''
  }

  async function regenerate() {
    await post(`/api/leads/${id}/regenerate`, {}, 'Analise/mensagem regerada.')
  }

  async function copyMessage() {
    const text = lead.message?.messageText || ''
    if (!text) return
    try {
      await navigator.clipboard.writeText(text)
      push('Mensagem copiada.', 'success')
    } catch {
      push('Nao foi possivel copiar.', 'error')
    }
  }

  if (loading && !lead)
    return <div className="loading">Carregando lead...</div>
  if (error && !lead)
    return <div className="error-state">Erro: {error}</div>
  if (!lead) return null

  const a = lead.analysis
  const m = lead.message
  const canRegenerate = a != null || lead.status === 'ERROR'

  return (
    <div>
      <div className="flex-between">
        <div>
          <button className="btn btn-sm" onClick={() => navigate(-1)}>
            Voltar
          </button>
          <h1 className="page-title" style={{ marginTop: 12 }}>
            {lead.businessName}
          </h1>
          <p className="page-sub">{lead.category || 'Lead'}</p>
        </div>
        <LeadStatusBadge status={lead.status} />
      </div>

      <div className="card" style={{ marginBottom: 20 }}>
        <div className="section-title" style={{ marginTop: 0 }}>
          Informacoes
        </div>
        <dl className="dl">
          <dt>Endereco</dt>
          <dd>{lead.address || '—'}</dd>
          <dt>Cidade/Estado</dt>
          <dd>
            {[lead.city, lead.state, lead.country].filter(Boolean).join(', ') ||
              '—'}
          </dd>
          <dt>Telefone</dt>
          <dd>{lead.phone || '—'}</dd>
          <dt>Website</dt>
          <dd>
            {lead.website ? (
              <a href={lead.website} target="_blank" rel="noreferrer">
                {lead.website}
              </a>
            ) : (
              '—'
            )}
          </dd>
          <dt>Instagram</dt>
          <dd>
            {lead.instagramUsername ? (
              <a
                href={lead.instagramUrl || `https://instagram.com/${lead.instagramUsername}`}
                target="_blank"
                rel="noreferrer"
              >
                {lead.instagramUsername} ({lead.instagramStatus})
              </a>
            ) : (
              '—'
            )}
          </dd>
          <dt>Fonte</dt>
          <dd>
            {lead.source || '—'}
            {lead.sourceId ? ` (${lead.sourceId})` : ''}
          </dd>
          <dt>Nao prospectar</dt>
          <dd>{lead.doNotContact ? 'Sim' : 'Nao'}</dd>
          <dt>Campanha</dt>
          <dd>
            {lead.campaignName || '—'}
            {lead.campaignId ? (
              <button
                className="btn btn-sm"
                style={{ marginLeft: 8 }}
                onClick={() => navigate(`/campanhas/${lead.campaignId}`)}
              >
                Ver campanha
              </button>
            ) : null}
          </dd>
          <dt>Criado em</dt>
          <dd>{formatDate(lead.createdAt)}</dd>
        </dl>
      </div>

      {a && (
        <div className="card" style={{ marginBottom: 20 }}>
          <div className="section-title" style={{ marginTop: 0 }}>
            Analise
          </div>
          <dl className="dl">
            <dt>Tipo de negocio</dt>
            <dd>{a.businessType || '—'}</dd>
            <dt>Servicos</dt>
            <dd>{a.services || '—'}</dd>
            <dt>Presenca digital</dt>
            <dd>{a.digitalPresence || '—'}</dd>
            <dt>Sistema de agendamento</dt>
            <dd>{a.bookingSystemStatus || '—'}</dd>
            <dt>Sistema detectado</dt>
            <dd>{a.detectedSystem || '—'}</dd>
            <dt>Sinais de atendimento</dt>
            <dd>{a.manualAttendanceSignals || '—'}</dd>
            <dt>Pontos de dor</dt>
            <dd>{a.painPoints || '—'}</dd>
            <dt>Oportunidade</dt>
            <dd>{a.commercialOpportunity || '—'}</dd>
            <dt>Score</dt>
            <dd>
              <strong>{a.opportunityScore != null ? a.opportunityScore : '—'}</strong>
            </dd>
            <dt>Modelo</dt>
            <dd>{a.model || '—'}</dd>
            <dt>Resumo</dt>
            <dd>{a.reasoningSummary || '—'}</dd>
            <dt>Analisado em</dt>
            <dd>{formatDate(a.analyzedAt)}</dd>
          </dl>
        </div>
      )}

      <div className="card" style={{ marginBottom: 20 }}>
        <div className="flex-between">
          <div className="section-title" style={{ margin: 0 }}>
            Mensagem gerada
          </div>
          <div className="row">
            {!editing && (
              <>
                <button
                  className="btn btn-sm"
                  onClick={() => setEditing(true)}
                  disabled={!m}
                >
                  Editar mensagem
                </button>
                <button
                  className="btn btn-sm"
                  onClick={copyMessage}
                  disabled={!m}
                >
                  <IconCopy width={14} height={14} />
                  Copiar
                </button>
                <a
                  className="btn btn-sm"
                  href={
                    lead.instagramUsername
                      ? `https://instagram.com/${lead.instagramUsername}`
                      : undefined
                  }
                  target="_blank"
                  rel="noreferrer"
                  onClick={(e) => {
                    if (!lead.instagramUsername) e.preventDefault()
                  }}
                  style={
                    lead.instagramUsername
                      ? {}
                      : { opacity: 0.5, pointerEvents: 'none' }
                  }
                >
                  <IconExternal width={14} height={14} />
                  Abrir Instagram
                </a>
              </>
            )}
          </div>
        </div>

        {m || editing ? (
          editing ? (
            <div style={{ marginTop: 12 }}>
              <textarea
                value={msgDraft}
                onChange={(e) => setMsgDraft(e.target.value)}
                rows={8}
                style={{ width: '100%', resize: 'vertical' }}
              />
              <div className="row" style={{ marginTop: 10 }}>
                <button
                  className="btn btn-primary btn-sm"
                  onClick={saveMessage}
                  disabled={saving}
                >
                  {saving ? 'Salvando...' : 'Salvar'}
                </button>
                <button
                  className="btn btn-sm"
                  onClick={() => {
                    setEditing(false)
                    setMsgDraft(m?.messageText || '')
                  }}
                >
                  Cancelar
                </button>
                {m?.edited && (
                  <span className="muted" style={{ fontSize: 12 }}>
                    (editada)
                  </span>
                )}
                {m?.approved && (
                  <span className="muted" style={{ fontSize: 12 }}>
                    (aprovada)
                  </span>
                )}
              </div>
            </div>
          ) : (
            <div className="message-box" style={{ marginTop: 12 }}>
              {m.messageText}
            </div>
          )
        ) : (
          <div className="muted" style={{ marginTop: 12 }}>
            Nenhuma mensagem gerada ainda.
          </div>
        )}
      </div>

      <div className="card">
        <div className="section-title" style={{ marginTop: 0 }}>
          Acoes
        </div>
        <div className="row">
          <button className="btn btn-primary btn-sm" onClick={approve}>
            Aprovar
          </button>
          <button className="btn btn-danger btn-sm" onClick={doNotContact}>
            Nao prospectar
          </button>
          <select
            defaultValue=""
            onChange={changeStatus}
            className="btn btn-sm"
            style={{ paddingRight: 8 }}
          >
            <option value="" disabled>
              Alterar status...
            </option>
            {STATUS_OPTIONS.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
          {canRegenerate && (
            <button className="btn btn-sm" onClick={regenerate}>
              <IconRefresh width={14} height={14} />
              Regenerar
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
