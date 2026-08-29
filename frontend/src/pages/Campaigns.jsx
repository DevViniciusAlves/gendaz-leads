import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api.js'
import { useToast } from '../components/Toast.jsx'
import { CampaignStatusBadge } from '../components/StatusBadge.jsx'
import { Modal } from '../components/Modal.jsx'
import { IconPlus } from '../components/Icons.jsx'
import { formatNumber } from '../format.js'

const PROCESSING = ['CREATED', 'DISCOVERING', 'ANALYZING', 'GENERATING']

export function Campaigns() {
  const [items, setItems] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [showForm, setShowForm] = useState(false)
  const navigate = useNavigate()
  const { push } = useToast()

  function load() {
    setLoading(true)
    api
      .get('/api/campaigns?page=0&size=20')
      .then((d) => setItems(d.content || []))
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false))
  }

  useEffect(load, [push])

  async function createCampaign(e) {
    e.preventDefault()
    const niche = e.target.niche.value.trim()
    const location = e.target.location.value.trim()
    const quantity = Number(e.target.quantity.value)
    if (!niche || !location) {
      push('Preencha nicho e localizacao.', 'error')
      return
    }
    try {
      const res = await api.post('/api/campaigns', {
        niche,
        location,
        quantity
      })
      push('Campanha criada.', 'success')
      setShowForm(false)
      navigate(`/campanhas/${res.id}`)
    } catch (err) {
      push(err.message, 'error')
    }
  }

  return (
    <div>
      <div className="flex-between">
        <div>
          <h1 className="page-title">Campanhas</h1>
          <p className="page-sub">Gere e acompanhe campanhas de prospeccao.</p>
        </div>
        <button className="btn btn-primary" onClick={() => setShowForm(true)}>
          <IconPlus width={15} height={15} />
          Gerar Leads
        </button>
      </div>

      {loading && <div className="loading">Carregando...</div>}
      {error && <div className="error-state">Erro: {error}</div>}

      {!loading && !error && items.length === 0 && (
        <div className="empty">
          Nenhuma campanha ainda. Clique em &quot;Gerar Leads&quot; para comecar.
        </div>
      )}

      {!loading && items.length > 0 && (
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Nome</th>
                <th>Status</th>
                <th>Descobertos</th>
                <th>Analisados</th>
                <th>Mensagens</th>
                <th>Aprovados</th>
                <th>Enviados</th>
                <th>Progresso</th>
              </tr>
            </thead>
            <tbody>
              {items.map((c) => {
                const pct =
                  c.progressTotal > 0
                    ? Math.round((c.progressCurrent / c.progressTotal) * 100)
                    : c.status === 'COMPLETED' ||
                      c.status === 'PARTIAL' ||
                      c.status === 'FAILED'
                    ? 100
                    : 0
                return (
                  <tr
                    key={c.id}
                    className="clickable"
                    onClick={() => navigate(`/campanhas/${c.id}`)}
                  >
                    <td>
                      <strong>{c.name}</strong>
                      <div className="muted" style={{ fontSize: 12 }}>
                        {c.niche} · {c.location}
                      </div>
                    </td>
                    <td>
                      <CampaignStatusBadge status={c.status} />
                    </td>
                    <td>{formatNumber(c.discoveredCount)}</td>
                    <td>{formatNumber(c.analyzedCount)}</td>
                    <td>{formatNumber(c.messageCount)}</td>
                    <td>{formatNumber(c.approvedCount)}</td>
                    <td>{formatNumber(c.sentCount)}</td>
                    <td style={{ minWidth: 120 }}>
                      <div className="progress">
                        <span style={{ width: `${pct}%` }} />
                      </div>
                      <div className="muted" style={{ fontSize: 11, marginTop: 4 }}>
                        {PROCESSING.includes(c.status)
                          ? `${c.progressCurrent}/${c.progressTotal}`
                          : c.status}
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {showForm && (
        <Modal
          title="Gerar Leads"
          onClose={() => setShowForm(false)}
          footer={
            <>
              <button className="btn" onClick={() => setShowForm(false)}>
                Cancelar
              </button>
              <button type="submit" form="campaign-form" className="btn btn-primary">
                Gerar
              </button>
            </>
          }
        >
          <form id="campaign-form" onSubmit={createCampaign}>
            <div className="field">
              <label htmlFor="niche">Nicho</label>
              <input id="niche" name="niche" placeholder="Ex: restaurantes" required />
            </div>
            <div className="field">
              <label htmlFor="location">Localizacao</label>
              <input id="location" name="location" placeholder="Ex: Sao Paulo" required />
            </div>
            <div className="field">
              <label htmlFor="quantity">Quantidade</label>
              <input
                id="quantity"
                name="quantity"
                type="number"
                min="3"
                max="30"
                defaultValue="10"
                required
              />
              <div className="hint">Minimo 3, maximo 30.</div>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}
