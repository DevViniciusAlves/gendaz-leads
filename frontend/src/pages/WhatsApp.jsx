import { useCallback, useEffect, useRef, useState } from 'react'
import QRCode from 'qrcode'
import { api } from '../api.js'
import { useToast } from '../components/Toast.jsx'

const POLL_MS = 4000

function badgeFor(status) {
  if (status === 'CONNECTED') return <span className="badge badge-green">Conectado</span>
  if (status === 'QR_REQUIRED' || status === 'CONNECTING')
    return <span className="badge badge-orange">Aguardando conexao</span>
  if (status === 'ERROR') return <span className="badge badge-red">Erro</span>
  return <span className="badge badge-gray">Desconectado</span>
}

export function WhatsApp() {
  const [status, setStatus] = useState(null)
  const [qr, setQr] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const { push } = useToast()
  const canvasRef = useRef(null)
  const timerRef = useRef(null)

  const fetchStatus = useCallback(async () => {
    try {
      const d = await api.get('/api/whatsapp/status')
      setStatus(d)
      setError('')
      return d
    } catch (err) {
      setError(err.message)
      return null
    }
  }, [])

  const fetchQr = useCallback(async () => {
    try {
      const d = await api.get('/api/whatsapp/qr')
      if (d && d.qr) setQr(d.qr)
    } catch {
      // QR pode ainda nao existir; o polling de status continua.
    }
  }, [])

  useEffect(() => {
    fetchStatus()
    return () => {
      if (timerRef.current) clearInterval(timerRef.current)
    }
  }, [fetchStatus])

  // Polling moderado enquanto nao conectado; para ao conectar.
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

  // Renderiza o QR no canvas quando disponivel.
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

  const s = status?.status || 'UNKNOWN'

  return (
    <div>
      <h1 className="page-title">WhatsApp</h1>
      <p className="page-sub">Integracao com WhatsApp via sessao propria do Gendaz Leads.</p>

      {error && <div className="error-state">Erro: {error}</div>}

      <div className="card" style={{ maxWidth: 520 }}>
        <div className="flex-between" style={{ marginBottom: 12 }}>
          <h3>WhatsApp</h3>
          {badgeFor(s)}
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
    </div>
  )
}
