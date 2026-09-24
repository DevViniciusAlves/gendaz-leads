import { useEffect, useState, useRef, useCallback } from 'react'
import { api } from '../api.js'
import { useToast } from '../components/Toast.jsx'
import { Modal } from '../components/Modal.jsx'
import { IconDatabase, IconSync, IconRefresh, IconPlus } from '../components/Icons.jsx'
import { formatNumber } from '../format.js'
import { buildOsmSyncRequest } from '../lib/osmSyncRequest.js'

const STATUS_COLORS = {
  READY: 'success',
  EMPTY: 'muted',
  QUEUED: 'warning',
  RUNNING: 'info',
  SUCCESS: 'success',
  PARTIAL: 'warning',
  EXHAUSTED: 'muted',
  FAILED: 'error'
}

function statusLabel(status) {
  switch (status) {
    case 'READY': return 'Pronto'
    case 'EMPTY': return 'Vazio'
    case 'QUEUED': return 'Na fila'
    case 'RUNNING': return 'Executando'
    case 'SUCCESS': return 'Sucesso'
    case 'PARTIAL': return 'Parcial'
    case 'EXHAUSTED': return 'Esgotado'
    case 'FAILED': return 'Falhou'
    default: return status
  }
}

const TERMINAL_SYNC_STATUSES = ['SUCCESS', 'PARTIAL', 'EXHAUSTED', 'FAILED']

export function OsmCatalog() {
  const [regions, setRegions] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [showForm, setShowForm] = useState(false)
  const [syncModalMode, setSyncModalMode] = useState(null)
  // null | 'NEW_CITY' | 'EXISTING_REGION'
  const [selectedRegion, setSelectedRegion] = useState(null)
  const [countries, setCountries] = useState([])
  const [countriesLoading, setCountriesLoading] = useState(true)
  const [countriesError, setCountriesError] = useState('')
  const [formSubmitting, setFormSubmitting] = useState(false)
  const { push } = useToast()

  const pollingIntervals = useRef({})
  const checkedActiveRuns = useRef(new Set())

  async function loadRegions() {
    setLoading(true)
    try {
      const data = await api.get('/api/osm-catalog/regions')
      setRegions(data)
      setError('')
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  function closeSyncModal() {
    setShowForm(false)
    setSyncModalMode(null)
    setSelectedRegion(null)
  }

  function openNewCitySync() {
    setSelectedRegion(null)
    setSyncModalMode('NEW_CITY')
    setShowForm(true)
  }

  function openExistingRegionSync(region) {
    setSelectedRegion(region)
    setSyncModalMode('EXISTING_REGION')
    setShowForm(true)
  }

  useEffect(() => {
    loadRegions()
  }, [])

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

  // Load sync runs for regions that have active syncs on initial load
  useEffect(() => {
    if (regions.length === 0) return

    regions.forEach((region) => {
      if (checkedActiveRuns.current.has(region.id)) {
        return
      }

      checkedActiveRuns.current.add(region.id)

      api
        .get(`/api/osm-catalog/regions/${region.id}/sync-runs`)
        .then((data) => {
          const latestRun = data?.[0]

          if (!latestRun) return

          setRegions((prev) =>
            prev.map((item) =>
              item.id === region.id
                ? { ...item, syncRuns: data }
                : item
            )
          )

          if (
            latestRun.status === 'QUEUED'
            || latestRun.status === 'RUNNING'
          ) {
            startPolling(latestRun.id)
          }
        })
        .catch(() => {})
    })
  }, [regions])

  async function requestSync(city, country, niche) {
    if (!city || !country || !niche) {
      push('Preencha cidade, país e nicho.', 'error')
      return
    }
    if (country !== 'br') {
      push('A sincronização local V1 suporta apenas Brasil.', 'error')
      return
    }
    setFormSubmitting(true)
    try {
      const { url, body } = buildOsmSyncRequest({ mode: 'NEW_CITY', city, country, niche })
      const response = await api.post(url, body)
      push('Sincronização iniciada. Verifique o status na tabela.', 'success')
      closeSyncModal()
      await loadRegions()
      applyAcceptedRun(response)
      if (response.syncRunId) {
        startPolling(response.syncRunId)
      }
    } catch (err) {
      push(err.message, 'error')
    } finally {
      setFormSubmitting(false)
    }
  }

  function applyAcceptedRun(response) {
    if (!response || !response.syncRunId || !response.regionId) {
      return
    }
    // Limpa erro legado: nova tentativa em andamento nao exibe falha antiga.
    setRegions((prev) =>
      prev.map((region) =>
        region.id === response.regionId
          ? {
              ...region,
              lastError: null,
              syncRuns: [
                {
                  id: response.syncRunId,
                  regionId: response.regionId,
                  city: response.city,
                  state: response.state,
                  country: response.country,
                  status: response.status || 'QUEUED',
                  requestedNiche: response.requestedNiche,
                  canonicalNiche: response.canonicalNiche,
                  targetValid: response.targetValid ?? 50
                }
              ]
            }
          : region
      )
    )

    checkedActiveRuns.current.add(response.regionId)
  }

  async function requestRegionSync(regionId, niche) {
    if (!regionId || !niche) {
      push('Informe o nicho para sincronizar novamente.', 'error')
      return
    }

    setFormSubmitting(true)

    try {
      const { url, body } = buildOsmSyncRequest({ mode: 'EXISTING_REGION', regionId, niche })
      const response = await api.post(url, body)

      push('Sincronização iniciada.', 'success')

      closeSyncModal()
      await loadRegions()
      applyAcceptedRun(response)
      if (response.syncRunId) {
        startPolling(response.syncRunId)
      }
    } catch (err) {
      push(err.message, 'error')
    } finally {
      setFormSubmitting(false)
    }
  }

  function startPolling(syncRunId) {
    if (pollingIntervals.current[syncRunId]) {
      return // Already polling
    }
    const interval = setInterval(async () => {
      try {
        const data = await api.get(`/api/osm-catalog/sync/${syncRunId}`)
        // Update region with latest sync run by regionId
        setRegions((prev) =>
          prev.map((region) =>
            region.id === data.regionId
              ? {
                  ...region,
                  syncRuns: [data]
                }
              : region
          )
        )

        if (TERMINAL_SYNC_STATUSES.includes(data.status)) {
          clearInterval(pollingIntervals.current[syncRunId])
          delete pollingIntervals.current[syncRunId]

          checkedActiveRuns.current.delete(data.regionId)

          loadRegions()

          return
        }
      } catch (err) {
        push(err.message, 'error')
        clearInterval(pollingIntervals.current[syncRunId])
        delete pollingIntervals.current[syncRunId]
      }
    }, 5000)
    pollingIntervals.current[syncRunId] = interval
  }

  function stopPolling(syncRunId) {
    if (pollingIntervals.current[syncRunId]) {
      clearInterval(pollingIntervals.current[syncRunId])
      delete pollingIntervals.current[syncRunId]
    }
  }

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      Object.values(pollingIntervals.current).forEach(clearInterval)
      pollingIntervals.current = {}
    }
  }, [])

  function handleSyncClick(region, isResync) {
    // Linha existente SEMPRE usa regionId: nunca chama o fluxo de cidade nova.
    openExistingRegionSync(region)
  }

  return (
    <div>
      <div className="flex-between">
        <div>
          <h1 className="page-title">
            <IconDatabase width={22} height={22} style={{ marginRight: 8, verticalAlign: 'middle' }} />
            Sincronizar
          </h1>
          <p className="page-sub">Atualize os dados de estabelecimentos antes de gerar novos leads.</p>
        </div>
        <button className="btn btn-primary" onClick={openNewCitySync} disabled={countriesLoading}>
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
                <th>Leads qualificados</th>
                <th>Progresso (50)</th>
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
                const displayStatus = currentRun ? currentRun.status : region.catalogStatus
                const statusColor = STATUS_COLORS[displayStatus] || 'muted'
                // Erro legado nao aparece durante tentativa ativa.
                const shouldShowError =
                  currentRun?.status === 'FAILED'
                  || (!currentRun && region.lastError)

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
                      {currentRun && currentRun.qualifiedSaved != null
                        ? `${formatNumber(currentRun.qualifiedSaved)} / ${formatNumber(currentRun.targetValid ?? 50)}`
                        : '—'}
                      {currentRun?.requestedNiche || currentRun?.canonicalNiche
                        ? ` · ${currentRun.requestedNiche || currentRun.canonicalNiche}`
                        : ''}
                    </td>
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
                        {isRunning && (
                          <button className="btn btn-sm" disabled>
                            <IconSync width={14} height={14} style={{ animation: 'spin 1s linear infinite' }} />
                            Aguardando...
                          </button>
                        )}
                        {!isRunning && region.catalogStatus === 'READY' && (
                          <button
                            className="btn btn-sm"
                            onClick={() => handleSyncClick(region, true)}
                          >
                            <IconSync width={14} height={14} />
                            Sincronizar Novamente
                          </button>
                        )}
                        {region.catalogStatus === 'EMPTY' && !isRunning && (
                          <button
                            className="btn btn-sm btn-primary"
                            onClick={() => handleSyncClick(region, false)}
                          >
                            <IconSync width={14} height={14} />
                            Iniciar Sync
                          </button>
                        )}
                      </div>
                      {shouldShowError && (
                        <div className="error-state" style={{ fontSize: 11, marginTop: 4 }}>
                          Erro: {currentRun?.status === 'FAILED' ? (currentRun.errorMessage || region.lastError) : region.lastError}
                        </div>
                      )}
                      {currentRun && currentRun.status === 'FAILED' && region.catalogStatus === 'READY' && (
                        <div className="hint" style={{ fontSize: 11, marginTop: 4, color: 'var(--warning)' }}>
                          Catálogo: READY (última sincronização falhou)
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
          title={syncModalMode === 'EXISTING_REGION' ? 'Sincronizar Região' : 'Sincronizar Cidade'}
          onClose={closeSyncModal}
          footer={
            <>
              <button className="btn" onClick={closeSyncModal}>
                Cancelar
              </button>
              <button type="submit" form="osm-sync-form" className="btn btn-primary" disabled={formSubmitting}>
                {formSubmitting ? 'Iniciando...' : 'Sincronizar'}
              </button>
            </>
          }
        >
          {syncModalMode === 'EXISTING_REGION' && selectedRegion ? (
            <form id="osm-sync-form" onSubmit={(e) => {
              e.preventDefault()
              const niche = e.target.niche.value.trim()
              requestRegionSync(selectedRegion.id, niche)
            }}>
              <div className="field">
                <label htmlFor="existing-city">Cidade</label>
                <input id="existing-city" name="city" value={selectedRegion.city || ''} readOnly disabled />
              </div>
              <div className="field">
                <label htmlFor="existing-state">Estado</label>
                <input id="existing-state" name="state" value={selectedRegion.state || ''} readOnly disabled />
              </div>
              <div className="field">
                <label htmlFor="existing-country">País</label>
                <input id="existing-country" name="country" value={selectedRegion.country || ''} readOnly disabled />
              </div>
              <div className="field">
                <label htmlFor="niche">Nicho</label>
                <input
                  id="niche"
                  name="niche"
                  placeholder="Ex: nail designer"
                  defaultValue={selectedRegion.syncRuns?.[0]?.requestedNiche || selectedRegion.syncRuns?.[0]?.canonicalNiche || ''}
                  required
                />
                <div className="hint">
                  Busca até 50 novos leads qualificados com WhatsApp validado e Instagram oficial.
                </div>
              </div>
            </form>
          ) : (
            <>
              {countriesError && (
                <div className="error-state">Erro ao carregar países: {countriesError}</div>
              )}
              <form id="osm-sync-form" onSubmit={(e) => {
                e.preventDefault()
                const city = e.target.city.value.trim()
                const country = e.target.country.value
                const niche = e.target.niche.value.trim()
                requestSync(city, country, niche)
              }}>
                <div className="field">
                  <label htmlFor="city">Cidade</label>
                  <input id="city" name="city" placeholder="Ex: Cuiabá" required />
                </div>
                <div className="field">
                  <label htmlFor="niche">Nicho</label>
                  <input
                    id="niche"
                    name="niche"
                    placeholder="Ex: nail designer"
                    required
                  />
                  <div className="hint">
                    Busca até 50 novos leads qualificados com WhatsApp validado e Instagram oficial.
                  </div>
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
            </>
          )}
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