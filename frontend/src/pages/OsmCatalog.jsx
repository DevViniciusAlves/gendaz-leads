import { useEffect, useState } from 'react'
import { api } from '../api.js'
import { useToast } from '../components/Toast.jsx'
import { Modal } from '../components/Modal.jsx'
import { IconDatabase, IconSync, IconRefresh, IconPlus } from '../components/Icons.jsx'
import { formatNumber } from '../format.js'

const STATUS_COLORS = {
  READY: 'success',
  EMPTY: 'muted',
  QUEUED: 'warning',
  RUNNING: 'info',
  SUCCESS: 'success',
  FAILED: 'error'
}

function statusLabel(status) {
  switch (status) {
    case 'READY': return 'Pronto'
    case 'EMPTY': return 'Vazio'
    case 'QUEUED': return 'Na fila'
    case 'RUNNING': return 'Executando'
    case 'SUCCESS': return 'Sucesso'
    case 'FAILED': return 'Falhou'
    default: return status
  }
}

export function OsmCatalog() {
  const [regions, setRegions] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [showForm, setShowForm] = useState(false)
  const [countries, setCountries] = useState([])
  const [countriesLoading, setCountriesLoading] = useState(true)
  const [countriesError, setCountriesError] = useState('')
  const [formSubmitting, setFormSubmitting] = useState(false)
  const { push } = useToast()

  function loadRegions() {
    setLoading(true)
    api.get('/api/osm-catalog/regions')
      .then((data) => {
        setRegions(data)
        setError('')
      })
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false))
  }

  useEffect(loadRegions, [push])

  useEffect(() => {
    api.get('/api/meta/countries')
      .then((data) => {
        setCountries(data)
        setCountriesLoading(false)
      })
      .catch((err) => {
        setCountriesError(err.message)
        setCountriesLoading(false)
      })
  }, [])

  async function requestSync(e) {
    e.preventDefault()
    const city = e.target.city.value.trim()
    const country = e.target.country.value
    if (!city || !country) {
      push('Preencha cidade e país.', 'error')
      return
    }
    if (country !== 'br') {
      push('A sincronização local V1 suporta apenas Brasil.', 'error')
      return
    }
    setFormSubmitting(true)
    try {
      await api.post('/api/osm-catalog/sync', { city, country })
      push('Sincronização iniciada. Verifique o status na tabela.', 'success')
      setShowForm(false)
      setTimeout(loadRegions, 2000)
    } catch (err) {
      push(err.message, 'error')
    } finally {
      setFormSubmitting(false)
    }
  }

  function getSyncRuns(regionId) {
    api.get(`/api/osm-catalog/regions/${regionId}/sync-runs`)
      .then((data) => {
        const region = regions.find(r => r.id === regionId)
        if (region) {
          region.syncRuns = data
          setRegions([...regions])
        }
      })
      .catch((err) => push(err.message, 'error'))
  }

  function startPolling(regionId) {
    const interval = setInterval(() => {
      getSyncRuns(regionId)
      const region = regions.find(r => r.id === regionId)
      if (region?.syncRuns?.[0]) {
        const status = region.syncRuns[0].status
        if (status === 'SUCCESS' || status === 'FAILED') {
          clearInterval(interval)
          loadRegions()
        }
      }
    }, 5000)
    return interval
  }

  return (
    <div>
      <div className="flex-between">
        <div>
          <h1 className="page-title">
            <IconDatabase width={22} height={22} style={{ marginRight: 8, verticalAlign: 'middle' }} />
            Catálogo OSM
          </h1>
          <p className="page-sub">Gerencie o catálogo local de estabelecimentos do OpenStreetMap.</p>
        </div>
        <button className="btn btn-primary" onClick={() => setShowForm(true)} disabled={countriesLoading}>
          <IconPlus width={15} height={15} />
          Sincronizar Cidade
        </button>
      </div>

      {loading && <div className="loading">Carregando...</div>}
      {error && <div className="error-state">Erro: {error}</div>}

      {!loading && regions.length === 0 && (
        <div className="empty">
          Nenhuma cidade sincronizada ainda. Clique em "Sincronizar Cidade" para começar.
        </div>
      )}

      {!loading && regions.length > 0 && (
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Cidade</th>
                <th>Estado</th>
                <th>País</th>
                <th>Status</th>
                <th>Estabelecimentos</th>
                <th>Última Sincronização</th>
                <th>Última Tentativa</th>
                <th>Sync Atual</th>
                <th>Ações</th>
              </tr>
            </thead>
            <tbody>
              {regions.map((region) => {
                const currentRun = region.syncRuns?.[0]
                const isRunning = currentRun && (currentRun.status === 'QUEUED' || currentRun.status === 'RUNNING')
                const statusColor = currentRun ? STATUS_COLORS[currentRun.status] : STATUS_COLORS[region.catalogStatus]

                return (
                  <tr key={region.id}>
                    <td>
                      <strong>{region.city}</strong>
                    </td>
                    <td>{region.state || '—'}</td>
                    <td>{region.country}</td>
                    <td>
                      <span className={`badge badge-${statusColor}`}>
                        {currentRun ? statusLabel(currentRun.status) : statusLabel(region.catalogStatus)}
                      </span>
                    </td>
                    <td>{formatNumber(region.placeCount)}</td>
                    <td>
                      {region.lastSuccessAt ? new Date(region.lastSuccessAt).toLocaleString('pt-BR') : '—'}
                    </td>
                    <td>
                      {region.lastAttemptAt ? new Date(region.lastAttemptAt).toLocaleString('pt-BR') : '—'}
                    </td>
                    <td>
                      {currentRun && (
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                          <span className={`badge badge-${statusColor}`}>
                            {statusLabel(currentRun.status)}
                          </span>
                          {isRunning && <IconSync width={14} height={14} style={{ animation: 'spin 1s linear infinite' }} />}
                        </div>
                      )}
                    </td>
                    <td>
                      <div style={{ display: 'flex', gap: 8 }}>
                        {!isRunning && region.catalogStatus === 'READY' && (
                          <button
                            className="btn btn-sm"
                            onClick={() => {
                              const interval = startPolling(region.id)
                              getSyncRuns(region.id)
                              region.pollingInterval = interval
                              setRegions([...regions])
                            }}
                          >
                            <IconSync width={14} height={14} />
                            Sincronizar Novamente
                          </button>
                        )}
                        {isRunning && (
                          <button className="btn btn-sm" disabled>
                            <IconSync width={14} height={14} style={{ animation: 'spin 1s linear infinite' }} />
                            Aguardando...
                          </button>
                        )}
                        {region.catalogStatus === 'EMPTY' && !isRunning && (
                          <button
                            className="btn btn-sm btn-primary"
                            onClick={() => {
                              const interval = startPolling(region.id)
                              getSyncRuns(region.id)
                              region.pollingInterval = interval
                              setRegions([...regions])
                            }}
                          >
                            <IconSync width={14} height={14} />
                            Iniciar Sync
                          </button>
                        )}
                      </div>
                      {region.lastError && (
                        <div className="error-state" style={{ fontSize: 11, marginTop: 4 }}>
                          Erro: {region.lastError}
                        </div>
                      )}
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
          title="Sincronizar Cidade"
          onClose={() => setShowForm(false)}
          footer={
            <>
              <button className="btn" onClick={() => setShowForm(false)}>
                Cancelar
              </button>
              <button type="submit" form="osm-sync-form" className="btn btn-primary" disabled={formSubmitting}>
                {formSubmitting ? 'Iniciando...' : 'Sincronizar'}
              </button>
            </>
          }
        >
          {countriesError && (
            <div className="error-state">Erro ao carregar países: {countriesError}</div>
          )}
          <form id="osm-sync-form" onSubmit={requestSync}>
            <div className="field">
              <label htmlFor="city">Cidade</label>
              <input id="city" name="city" placeholder="Ex: Cuiabá" required />
            </div>
            <div className="field">
              <label htmlFor="country">País</label>
              <select
                id="country"
                name="country"
                defaultValue="br"
                required
                disabled={countriesLoading || countries.length === 0}
              >
                {countries.map((country) => (
                  <option key={country.code} value={country.code}>
                    {country.name}
                  </option>
                ))}
              </select>
              {countriesLoading && <div className="hint">Carregando países...</div>}
              {countries.length === 0 && !countriesLoading && !countriesError && (
                <div className="hint">Nenhum país disponível</div>
              )}
              <div className="hint" style={{ color: 'var(--warning)' }}>
                V1 suporta apenas Brasil. Selecione Brasil.
              </div>
            </div>
          </form>
        </Modal>
      )}
      <style jsx>{`
        @keyframes spin {
          from { transform: rotate(0deg); }
          to { transform: rotate(360deg); }
        }
      `}</style>
    </div>
  )
}