import { useEffect, useState, useRef, useCallback } from 'react'
import { api } from '../api.js'
import { useToast } from '../components/Toast.jsx'
import { Modal } from '../components/Modal.jsx'
import { IconDatabase, IconSync, IconPlus } from '../components/Icons.jsx'
import { formatNumber } from '../format.js'
import { buildOsmSyncRequest, newIdempotencyKey } from '../lib/osmSyncRequest.js'
import { syncErrorMessage, isTerminalRunStatus, isActiveRunStatus } from '../lib/syncErrors.js'

const STATUS_COLORS = {
  READY: 'success',
  EMPTY: 'muted',
  CONFIGURATION_REQUIRED: 'warning',
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
    case 'CONFIGURATION_REQUIRED': return 'Configurar'
    case 'QUEUED': return 'Na fila'
    case 'RUNNING': return 'Executando'
    case 'SUCCESS': return 'Concluído'
    case 'PARTIAL': return 'Concluído — parcial'
    case 'EXHAUSTED': return 'Concluído'
    case 'FAILED': return 'Falhou'
    default: return status
  }
}

function runHeadline(run, target) {
  if (!run) return '—'
  const saved = run.qualifiedSaved ?? 0
  const targetValid = run.targetValid ?? target?.targetValid ?? 50
  switch (run.status) {
    case 'SUCCESS': return `Concluído — ${formatNumber(saved)} novos leads`
    case 'PARTIAL': return `Concluído — ${formatNumber(saved)} novos de ${formatNumber(targetValid)}`
    case 'EXHAUSTED': return `Concluído — ${formatNumber(saved)} novos leads`
    case 'FAILED': return 'Falhou'
    default: return statusLabel(run.status)
  }
}

function runDetail(run) {
  if (!run) return null
  const dup = (run.discardedDuplicateSource ?? 0) + (run.discardedDuplicatePhone ?? 0)
    + (run.discardedDuplicateInstagram ?? 0)
  return {
    potenciais: run.potentialNicheCandidates ?? run.candidatesScanned ?? '—',
    confirmados: run.nicheConfirmed ?? run.nicheMatches ?? '—',
    semInstagram: run.discardedNoInstagram ?? '—',
    semTelefone: run.discardedNoPhone ?? run.discardedNoPhone ?? '—',
    semWhatsApp: run.discardedNotOnWhatsApp ?? '—',
    duplicados: dup,
    novosSalvos: run.qualifiedSaved ?? '—',
  }
}

const POLL_INTERVAL_MS = 5000
const RECONCILE_WINDOW_MS = 2 * 60 * 1000

export function OsmCatalog() {
  const [targets, setTargets] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [showNewModal, setShowNewModal] = useState(false)
  const [formSubmitting, setFormSubmitting] = useState(false)
  const [syncingTargets, setSyncingTargets] = useState(() => new Set())
  const [expandedRuns, setExpandedRuns] = useState(() => new Set())
  const { push } = useToast()

  const pollingIntervals = useRef({})
  const pollRetries = useRef({})
  const mounted = useRef(true)

  const loadTargets = useCallback(async ({ silent = false } = {}) => {
    if (!silent) setLoading(true)
    try {
      const data = await api.get('/api/osm-catalog/targets')
      if (!mounted.current) return data
      setTargets(Array.isArray(data) ? data : [])
      setError('')
      return data
    } catch (err) {
      if (!mounted.current) throw err
      setError(err.message)
      throw err
    } finally {
      if (!mounted.current) return
      if (!silent) setLoading(false)
    }
  }, [])

  function applyAcceptedRun(response) {
    if (!response || !response.syncRunId) return
    setTargets((prev) =>
      prev.map((t) => {
        const matchByTarget = response.targetId != null && t.id === response.targetId
        const matchByRegion = response.targetId == null && response.regionId != null && t.regionId === response.regionId
        if (!matchByTarget && !matchByRegion) return t
        return {
          ...t,
          lastError: null,
          lastAttemptAt: new Date().toISOString(),
          latestRun: {
            id: response.syncRunId,
            targetId: response.targetId ?? t.id,
            regionId: response.regionId ?? t.regionId,
            city: response.city ?? t.city,
            state: response.state ?? t.state,
            country: response.country ?? t.country,
            status: response.status || 'QUEUED',
            requestedNiche: response.requestedNiche ?? t.requestedNiche,
            canonicalNiche: response.canonicalNiche ?? t.canonicalNiche,
            targetValid: response.targetValid ?? t.targetValid ?? 50,
            qualifiedSaved: 0
          }
        }
      })
    )
  }

  const stopPolling = useCallback((runId) => {
    if (pollingIntervals.current[runId]) {
      clearInterval(pollingIntervals.current[runId])
      delete pollingIntervals.current[runId]
    }
    delete pollRetries.current[runId]
  }, [])

  const fetchRunAndUpdate = useCallback(async (runId) => {
    const data = await api.get(`/api/osm-catalog/sync/${runId}`)
    if (!mounted.current) return data
    setTargets((prev) =>
      prev.map((t) => {
        const isTarget = (data.targetId != null && t.id === data.targetId)
          || (data.targetId == null && data.regionId != null && t.regionId === data.regionId)
        if (!isTarget) return t
        return { ...t, latestRun: data, lastAttemptAt: t.lastAttemptAt }
      })
    )
    return data
  }, [])

  const startPolling = useCallback((runId) => {
    if (!runId || pollingIntervals.current[runId]) return
    pollRetries.current[runId] = 0
    const tick = async () => {
      try {
        const data = await fetchRunAndUpdate(runId)
        pollRetries.current[runId] = 0
        if (isTerminalRunStatus(data.status)) {
          stopPolling(runId)
          // Recarrega o target para atualizar pool count e limpar erro antigo.
          try {
            await loadTargets({ silent: true })
          } catch { /* mantém último estado */ }
        }
      } catch {
        // Erro transitório não apaga sync real: retry curto antes de abandonar.
        pollRetries.current[runId] = (pollRetries.current[runId] || 0) + 1
        if (pollRetries.current[runId] >= 3) {
          stopPolling(runId)
          try {
            await loadTargets({ silent: true })
          } catch { /* mantém último estado */ }
        }
      }
    }
    // Poll imediato após 202, depois intervalo controlado.
    tick()
    pollingIntervals.current[runId] = setInterval(tick, POLL_INTERVAL_MS)
  }, [fetchRunAndUpdate, loadTargets, stopPolling])

  // Carga inicial: uma chamada GET /targets (sem N+1), retoma polling de runs ativos.
  useEffect(() => {
    mounted.current = true
    loadTargets()
      .then((data) => {
        if (!Array.isArray(data)) return
        for (const t of data) {
          if (t?.latestRun && isActiveRunStatus(t.latestRun.status)) {
            startPolling(t.latestRun.id)
          }
        }
      })
      .catch(() => {})
    return () => {
      mounted.current = false
      Object.values(pollingIntervals.current).forEach(clearInterval)
      pollingIntervals.current = {}
    }
  }, [loadTargets, startPolling])

  function closeNewModal() {
    if (formSubmitting) return
    setShowNewModal(false)
  }

  async function reconcileAfterUncertainOutcome(targetId, intentKey) {
    // Resposta perdida / 409: recarrega targets e procura run ativo ou recém-criado.
    try {
      const data = await loadTargets({ silent: true })
      const found = (Array.isArray(data) ? data : []).find((t) => t.id === targetId)
      const run = found?.latestRun
      if (!run) return null
      const createdAt = run.createdAt ? new Date(run.createdAt).getTime() : 0
      const recent = Date.now() - createdAt < RECONCILE_WINDOW_MS
      if (isActiveRunStatus(run.status) || recent) {
        return run
      }
      return null
    } catch {
      return null
    }
  }

  async function requestNewSync(city, niche) {
    if (!city || !niche) {
      push('Preencha cidade e nicho.', 'error')
      return
    }
    const intentKey = newIdempotencyKey()
    setFormSubmitting(true)
    try {
      const { url, body } = buildOsmSyncRequest({ mode: 'NEW_CITY', city, country: 'br', niche })
      const response = await api.post(url, body, { headers: { 'Idempotency-Key': intentKey } })
      push('Sincronização iniciada. Verifique o status na tabela.', 'success')
      setShowNewModal(false)
      await loadTargets({ silent: true })
      applyAcceptedRun(response)
      if (response.syncRunId) startPolling(response.syncRunId)
    } catch (err) {
      push(syncErrorMessage(err), 'error')
    } finally {
      setFormSubmitting(false)
    }
  }

  async function requestResync(targetId) {
    // Sincronizar Novamente: um clique, sem modal, sem pedir dados. Usa target persistido.
    if (syncingTargets.has(targetId)) return
    const intentKey = newIdempotencyKey()
    setSyncingTargets((prev) => new Set(prev).add(targetId))
    // Limpa erro visual anterior imediatamente.
    setTargets((prev) => prev.map((t) => (t.id === targetId ? { ...t, lastError: null } : t)))
    try {
      const { url, body } = buildOsmSyncRequest({ mode: 'TARGET_RESYNC', targetId })
      const response = await api.post(url, body, { headers: { 'Idempotency-Key': intentKey } })
      push('Sincronização iniciada.', 'success')
      await loadTargets({ silent: true })
      applyAcceptedRun(response)
      if (response.syncRunId) startPolling(response.syncRunId)
    } catch (err) {
      if (err && (err.status === 409 || err.code === 'OSM_SYNC_ALREADY_RUNNING')) {
        const run = await reconcileAfterUncertainOutcome(targetId, intentKey)
        if (run) {
          push('Já existe uma sincronização em andamento para este target.', 'info')
          applyAcceptedRun({
            syncRunId: run.id, targetId, regionId: run.regionId,
            status: run.status, requestedNiche: run.requestedNiche,
            canonicalNiche: run.canonicalNiche, targetValid: run.targetValid
          })
          startPolling(run.id)
          return
        }
      }
      // Erro de rede/resposta perdida depois do dispatch: reconcilia antes de falhar.
      if (err && (err.message || '').toLowerCase().includes('fetch')
        || err?.status == null || err?.status >= 500) {
        const run = await reconcileAfterUncertainOutcome(targetId, intentKey)
        if (run) {
          push('Sincronização iniciada.', 'success')
          applyAcceptedRun({
            syncRunId: run.id, targetId, regionId: run.regionId,
            status: run.status, requestedNiche: run.requestedNiche,
            canonicalNiche: run.canonicalNiche, targetValid: run.targetValid
          })
          startPolling(run.id)
          return
        }
      }
      push(syncErrorMessage(err), 'error')
      try {
        await loadTargets({ silent: true })
      } catch { /* mantém estado */ }
    } finally {
      setSyncingTargets((prev) => {
        const next = new Set(prev)
        next.delete(targetId)
        return next
      })
    }
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
        <button className="btn btn-primary" onClick={() => setShowNewModal(true)}>
          <IconPlus width={15} height={15} />
          Sincronizar
        </button>
      </div>

      {loading && <div className="loading">Carregando...</div>}
      {error && <div className="error-state">Erro: {error}</div>}

      {!loading && targets.length === 0 && (
        <div className="empty">
          Nenhuma sincronização ainda. Clique em &quot;Sincronizar&quot; para começar.
        </div>
      )}

      {!loading && targets.length > 0 && (
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Cidade</th>
                <th>Estado</th>
                <th>Nicho</th>
                <th>Pool</th>
                <th>Disponíveis</th>
                <th>Último Run</th>
                <th>Progresso</th>
                <th>Última Sincronização</th>
                <th>Última Tentativa</th>
                <th>Ações</th>
              </tr>
            </thead>
            <tbody>
              {targets.map((target) => {
                const run = target.latestRun
                const running = run && isActiveRunStatus(run.status)
                const starting = syncingTargets.has(target.id)
                const busy = running || starting
                const poolColor = STATUS_COLORS[target.poolStatus] || 'muted'
                const runColor = run ? (STATUS_COLORS[run.status] || 'muted') : 'muted'
                const showError = run?.status === 'FAILED' || (!run && target.lastError)
                return (
                  <tr key={target.id}>
                    <td><strong>{target.city}</strong></td>
                    <td>{target.state || '—'}</td>
                    <td>{target.requestedNiche || target.canonicalNiche}</td>
                    <td>
                      <span className={`badge badge-${poolColor}`}>
                        {target.poolStatus === 'READY'
                          ? `Pronto (${formatNumber(target.qualifiedCount ?? 0)})`
                          : statusLabel(target.poolStatus)}
                      </span>
                    </td>
                    <td>{formatNumber(target.availableNewCount ?? 0)}</td>
                    <td>
                      {run
                        ? <span className={`badge badge-${runColor}`}>{runHeadline(run, target)}</span>
                        : '—'}
                    </td>
                    <td>
                      {run && run.qualifiedSaved != null
                        ? `${formatNumber(run.qualifiedSaved)} / ${formatNumber(run.targetValid ?? target.targetValid ?? 50)}`
                        : '—'}
                      {run && (run.status === 'PARTIAL' || run.status === 'EXHAUSTED' || run.status === 'SUCCESS')
                        ? (
                          <div style={{ marginTop: 4 }}>
                            <button
                              className="btn btn-sm"
                              onClick={() => setExpandedRuns((prev) => {
                                const next = new Set(prev)
                                if (next.has(run.id)) next.delete(run.id)
                                else next.add(run.id)
                                return next
                              })}
                            >
                              {expandedRuns.has(run.id) ? 'Ocultar detalhes' : 'Ver detalhes'}
                            </button>
                            {expandedRuns.has(run.id) && (() => {
                              const d = runDetail(run)
                              if (!d) return null
                              return (
                                <div className="hint" style={{ fontSize: 11, marginTop: 4, lineHeight: 1.6 }}>
                                  potenciais: {d.potenciais} · confirmados: {d.confirmados}<br />
                                  sem Instagram: {d.semInstagram} · sem telefone: {d.semTelefone}<br />
                                  sem WhatsApp: {d.semWhatsApp} · duplicados: {d.duplicados}<br />
                                  novos salvos: {d.novosSalvos}
                                  {run.status === 'PARTIAL' && (
                                    <><br />Pool possui apenas {formatNumber(run.qualifiedSaved ?? 0)} novos disponíveis.</>
                                  )}
                                  {run.status === 'EXHAUSTED' && (run.qualifiedSaved ?? 0) === 0 && (
                                    <><br />Nenhum candidato com Instagram + telefone + WhatsApp no dataset oficial.</>
                                  )}
                                </div>
                              )
                            })()}
                          </div>
                        ) : null}
                    </td>
                    <td>{target.lastSuccessAt ? new Date(target.lastSuccessAt).toLocaleString('pt-BR') : '—'}</td>
                    <td>{target.lastAttemptAt ? new Date(target.lastAttemptAt).toLocaleString('pt-BR') : '—'}</td>
                    <td>
                      <div style={{ display: 'flex', gap: 8 }}>
                        <button
                          className="btn btn-sm"
                          disabled={busy}
                          onClick={() => requestResync(target.id)}
                        >
                          <IconSync width={14} height={14} className={busy ? 'spin' : ''} />
                          {starting ? 'Iniciando...' : running ? 'Sincronizando...' : 'Sincronizar Novamente'}
                        </button>
                      </div>
                      {showError && (
                        <div className="error-state" style={{ fontSize: 11, marginTop: 4 }}>
                          Erro: {run?.status === 'FAILED' ? (run.errorMessage || target.lastError) : target.lastError}
                        </div>
                      )}
                      {run?.status === 'FAILED' && target.poolStatus === 'READY' && (
                        <div className="hint" style={{ fontSize: 11, marginTop: 4 }}>
                          Pool: Pronto ({formatNumber(target.qualifiedCount ?? 0)} leads) — última sincronização falhou
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

      {showNewModal && (
        <Modal
          title="Sincronizar"
          onClose={closeNewModal}
          footer={
            <>
              <button className="btn" onClick={closeNewModal} disabled={formSubmitting}>
                Cancelar
              </button>
              <button type="submit" form="osm-sync-form" className="btn btn-primary" disabled={formSubmitting}>
                {formSubmitting ? 'Iniciando...' : 'Sincronizar'}
              </button>
            </>
          }
        >
          <form id="osm-sync-form" onSubmit={(e) => {
            e.preventDefault()
            const city = e.target.city.value.trim()
            const niche = e.target.niche.value.trim()
            requestNewSync(city, niche)
          }}>
            <div className="field">
              <label htmlFor="city">Cidade</label>
              <input id="city" name="city" placeholder="Ex: Cuiabá" required />
            </div>
            <div className="field">
              <label htmlFor="niche">Nicho</label>
              <input id="niche" name="niche" placeholder="Ex: nail designer" required />
              <div className="hint">
                Busca até 50 novos leads qualificados com WhatsApp validado e Instagram oficial.
              </div>
            </div>
            <div className="field">
              <label htmlFor="country">País</label>
              <input id="country" name="country" value="Brasil" readOnly disabled />
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}
