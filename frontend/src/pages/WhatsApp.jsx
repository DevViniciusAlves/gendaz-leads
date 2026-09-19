import { useCallback, useEffect, useRef, useState } from 'react'
import QRCode from 'qrcode'
import { api } from '../api.js'
import { useToast } from '../components/Toast.jsx'

const POLL_MS = 4000
const ALLOWED_VARS = ['{{nome}}', '{{cidade}}', '{{nicho}}', '{{instagram}}']
const VAR_REGEX = /\{\{[^{}]+\}\}/g

function badgeFor(status) {
  if (status === 'CONNECTED') return <span className="badge badge-green">Conectado</span>
  if (status === 'QR_REQUIRED' || status === 'CONNECTING')
    return <span className="badge badge-orange">Aguardando conexao</span>
  if (status === 'ERROR') return <span className="badge badge-red">Erro</span>
  if (status === 'LOGGED_OUT') return <span className="badge badge-gray">Desconectado</span>
  return <span className="badge badge-gray">Desconectado</span>
}

export function WhatsApp() {
  const [status, setStatus] = useState(null)
  const [qr, setQr] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [statusLoading, setStatusLoading] = useState(true)
  const [templateText, setTemplateText] = useState('')
  const [templateLoading, setTemplateLoading] = useState(true)
  const [templateSaving, setTemplateSaving] = useState(false)
  const [templateUpdatedAt, setTemplateUpdatedAt] = useState(null)
  const { push } = useToast()
  const canvasRef = useRef(null)
  const timerRef = useRef(null)
  const textareaRef = useRef(null)

  const fetchStatus = useCallback(async () => {
    try {
      const d = await api.get('/api/whatsapp/status')
      setStatus(d)
      if (d && d.status === 'CONNECTED') {
        setQr('')
      }
      setError('')
      return d
    } catch (err) {
      setError(err.message)
      return null
    } finally {
      setStatusLoading(false)
    }
  }, [])

  const fetchQr = useCallback(async () => {
    try {
      const d = await api.get('/api/whatsapp/qr')
      if (d && d.qr) setQr(d.qr)
    } catch (err) {
      // 404 qr_not_available após scan: não exibir erro vermelho.
      if (err && err.status === 404) return
      // QR pode ainda não existir; o polling de status continua.
    }
  }, [])

  const fetchTemplate = useCallback(async () => {
    setTemplateLoading(true)
    try {
      const d = await api.get('/api/message-templates/default')
      if (d && d.templateText) {
        setTemplateText(d.templateText)
        setTemplateUpdatedAt(d.updatedAt || null)
      }
    } catch (err) {
      if (err && err.status !== 404) {
        push('Erro ao carregar abordagem: ' + err.message, 'error')
      }
    } finally {
      setTemplateLoading(false)
    }
  }, [push])

  useEffect(() => {
    fetchStatus()
    fetchTemplate()
    return () => {
      if (timerRef.current) clearInterval(timerRef.current)
    }
  }, [fetchStatus, fetchTemplate])

  // Polling moderado enquanto não conectado; para ao conectar.
  useEffect(() => {
    if (timerRef.current) {
      clearInterval(timerRef.current)
      timerRef.current = null
    }
    const s = status?.status
    if (!s || s === 'CONNECTED') return
    if (s === 'QR_REQUIRED' || s === 'CONNECTING') {
      fetchQr()
      timerRef.current = setInterval(async () => {
        const d = await fetchStatus()
        if (d && (d.status === 'QR_REQUIRED' || d.status === 'CONNECTING')) {
          fetchQr()
        }
      }, POLL_MS)
    }
    return () => {
      if (timerRef.current) clearInterval(timerRef.current)
    }
  }, [status?.status, fetchStatus, fetchQr])

  // Renderiza o QR no canvas quando disponível.
  useEffect(() => {
    if (qr && canvasRef.current) {
      QRCode.toCanvas(canvasRef.current, qr, { width: 240, margin: 2 }).catch(() => {})
    }
  }, [qr])

  async function connect() {
    setBusy(true)
    setError('')
    try {
      const d = await api.post('/api/whatsapp/connect', {})
      setStatus(d)
      if (d.status === 'QR_REQUIRED' || d.status === 'CONNECTING') fetchQr()
    } catch (err) {
      setError(err.message)
      push(err.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  async function disconnect() {
    setBusy(true)
    setError('')
    try {
      const d = await api.post('/api/whatsapp/disconnect', {})
      setStatus({ status: d.status || 'LOGGED_OUT', hasQr: false })
      setQr('')
      push('WhatsApp desconectado.', 'success')
    } catch (err) {
      setError(err.message)
      push(err.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  function insertVar(v) {
    const el = textareaRef.current
    if (!el) {
      setTemplateText((prev) => prev + v)
      return
    }
    const start = el.selectionStart ?? templateText.length
    const end = el.selectionEnd ?? templateText.length
    const next = templateText.substring(0, start) + v + templateText.substring(end)
    setTemplateText(next)
    requestAnimationFrame(() => {
      el.focus()
      const pos = start + v.length
      try {
        el.setSelectionRange(pos, pos)
      } catch (_) {}
    })
  }

  const invalidVars = []
  const matches = templateText.match(VAR_REGEX) || []
  matches.forEach((m) => {
    if (!ALLOWED_VARS.includes(m)) invalidVars.push(m)
  })
  const templateInvalid = invalidVars.length > 0 || templateText.trim().length === 0

  async function saveTemplate() {
    if (templateInvalid) return
    setTemplateSaving(true)
    try {
      const res = await api.put('/api/message-templates/default', {
        templateText,
        name: 'Abordagem padrão'
      })
      setTemplateUpdatedAt(res.updatedAt || new Date().toISOString())
      push('Abordagem salva com sucesso.', 'success')
    } catch (err) {
      push('Erro ao salvar abordagem: ' + err.message, 'error')
    } finally {
      setTemplateSaving(false)
    }
  }

  const s = status?.status || 'UNKNOWN'

  return (
    <div>
      <h1 className="page-title">WhatsApp</h1>
      <p className="page-sub">Conecte sua sessão e configure a abordagem usada nas campanhas.</p>

      {error && <div className="error-state">Erro: {error}</div>}

      <div className="card" style={{ maxWidth: 520, marginBottom: 16 }}>
        <div className="flex-between" style={{ marginBottom: 12 }}>
          <h3>Conexão WhatsApp</h3>
          {statusLoading ? <span className="badge badge-gray">Carregando...</span> : badgeFor(s)}
        </div>

        {s === 'CONNECTED' ? (
          <div>
            <p className="muted">Conectado</p>
            <button className="btn btn-danger" onClick={disconnect} disabled={busy}>
              Desconectar
            </button>
          </div>
        ) : s === 'QR_REQUIRED' || s === 'CONNECTING' ? (
          <div>
            <p className="muted">Aguardando conexao</p>
            <div className="qr-box">
              {qr ? (
                <canvas ref={canvasRef} />
              ) : (
                <div className="loading">Gerando QR Code...</div>
              )}
            </div>
            <p className="muted">Escaneie com seu WhatsApp</p>
          </div>
        ) : (
          <div>
            <p className="muted">Desconectado</p>
            <button className="btn btn-primary" onClick={connect} disabled={busy}>
              Conectar WhatsApp
            </button>
          </div>
        )}
      </div>

      <div className="card" style={{ maxWidth: 520 }}>
        <h3 style={{ marginBottom: 4 }}>Abordagem padrão</h3>
        <p className="muted" style={{ marginBottom: 12 }}>
          Variáveis: {ALLOWED_VARS.map((v) => `[${v}]`).join(' ')}
        </p>

        {templateLoading ? (
          <div className="loading">Carregando abordagem...</div>
        ) : (
          <div>
            <div style={{ display: 'flex', gap: 8, marginBottom: 8, flexWrap: 'wrap' }}>
              {ALLOWED_VARS.map((v) => (
                <button key={v} type="button" className="btn btn-sm" onClick={() => insertVar(v)}>
                  {v}
                </button>
              ))}
            </div>
            <textarea
              ref={textareaRef}
              value={templateText}
              onChange={(e) => setTemplateText(e.target.value)}
              rows={8}
              style={{
                width: '100%',
                fontFamily: 'monospace',
                padding: 12,
                border: invalidVars.length > 0 ? '1px solid red' : '1px solid #ccc'
              }}
              placeholder={'Olá {{nome}}, tudo bem?\n\nVi a {{nome}} e queria apresentar o Gendaz.\nVocê atende em {{cidade}}?'}
            />
            {invalidVars.length > 0 && (
              <div className="error-state" style={{ marginTop: 8, textAlign: 'left' }}>
                Variáveis inválidas: {invalidVars.join(', ')}. Use apenas {ALLOWED_VARS.join(', ')}.
              </div>
            )}
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 12 }}>
              <button
                type="button"
                className="btn btn-primary"
                onClick={saveTemplate}
                disabled={templateSaving || templateInvalid}
              >
                {templateSaving ? 'Salvando...' : 'Salvar abordagem'}
              </button>
              {templateUpdatedAt && (
                <span className="muted" style={{ fontSize: 12 }}>
                  Última atualização: {new Date(templateUpdatedAt).toLocaleString('pt-BR')}
                </span>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
