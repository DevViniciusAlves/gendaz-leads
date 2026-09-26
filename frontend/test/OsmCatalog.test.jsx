import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import { OsmCatalog } from '../src/pages/OsmCatalog.jsx'
import { ToastProvider } from '../src/components/Toast.jsx'
import { api } from '../src/api.js'

vi.mock('../src/api.js', () => ({
  api: { get: vi.fn(), post: vi.fn() }
}))

const targetReady = (over = {}) => ({
  id: 10,
  regionId: 2,
  city: 'Cuiabá',
  state: 'Mato Grosso',
  country: 'Brasil',
  requestedNiche: 'NAIL DESIGNER',
  canonicalNiche: 'nails',
  targetValid: 50,
  qualifiedCount: 17,
  availableNewCount: 14,
  poolStatus: 'READY',
  lastSuccessAt: '2026-01-01T10:00:00Z',
  lastAttemptAt: '2026-01-02T10:00:00Z',
  lastError: null,
  latestRun: null,
  ...over
})

function renderCatalog() {
  return render(
    <ToastProvider>
      <OsmCatalog />
    </ToastProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers({ shouldAdvanceTime: true })
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('OsmCatalog targets', () => {
  it('nova cidade abre modal; target exibe nicho e pool target-specific', async () => {
    api.get.mockResolvedValue([targetReady()])
    renderCatalog()
    await waitFor(() => expect(screen.getByText('Cuiabá')).toBeInTheDocument())
    expect(screen.getByText('NAIL DESIGNER')).toBeInTheDocument()
    expect(screen.getByText(/Pronto \(17\)/)).toBeInTheDocument()
    expect(screen.getByText('14')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Sincronizar$/ }))
    expect(await screen.findByPlaceholderText('Ex: Cuiabá', {}, { timeout: 3000 })).toBeInTheDocument()
  })

  it('resync NAO abre modal, envia target id sem niche, desabilita no duplo clique', async () => {
    api.get.mockResolvedValue([targetReady()])
    api.post.mockImplementation(() => new Promise(() => {})) // pendente
    renderCatalog()
    await waitFor(() => expect(screen.getByText('Sincronizar Novamente')).toBeInTheDocument())

    const btn = screen.getByRole('button', { name: /Sincronizar Novamente/ })
    const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) })
    await user.click(btn)
    await user.click(btn) // duplo clique

    await waitFor(() => {
      expect(api.post).toHaveBeenCalledTimes(1)
    })
    expect(api.post).toHaveBeenCalledWith(
      '/api/osm-catalog/targets/10/sync',
      {},
      expect.objectContaining({ headers: expect.objectContaining({ 'Idempotency-Key': expect.any(String) }) })
    )
    // Nenhum modal aberto pelo resync.
    expect(screen.queryByLabelText('Nicho')).toBeNull()
    expect(screen.getByRole('button', { name: /Iniciando/ })).toBeDisabled()
  })

  it('202 aplica latestRun e polling RUNNING->SUCCESS atualiza', async () => {
    const running = { id: 30, targetId: 10, regionId: 2, status: 'RUNNING', qualifiedSaved: 8, targetValid: 50 }
    const success = { ...running, status: 'SUCCESS', qualifiedSaved: 50 }
    api.get.mockImplementation(async (path) => {
      if (path === '/api/osm-catalog/targets') return [targetReady()]
      if (path === '/api/osm-catalog/sync/30') return running
      return null
    })
    api.post.mockResolvedValue({ syncRunId: 30, targetId: 10, regionId: 2, status: 'QUEUED' })
    renderCatalog()
    await waitFor(() => expect(screen.getByText('Sincronizar Novamente')).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /Sincronizar Novamente/ }))
    await waitFor(() => expect(screen.getByText('Sincronizando...')).toBeInTheDocument())

    // Avança polling: RUNNING mantém, depois SUCCESS recarrega target.
    api.get.mockImplementation(async (path) => {
      if (path === '/api/osm-catalog/targets') return [targetReady({ latestRun: success })]
      if (path === '/api/osm-catalog/sync/30') return success
      return null
    })
    await vi.advanceTimersByTimeAsync(6000)
    await waitFor(() => expect(screen.getByText('Sucesso')).toBeInTheDocument())
  })

  it('polling PARTIAL/EXHAUSTED/FAILED exibe status sem confundir com pool', async () => {
    for (const status of ['PARTIAL', 'EXHAUSTED', 'FAILED']) {
      vi.clearAllMocks()
      const run = { id: 31, targetId: 10, regionId: 2, status, qualifiedSaved: status === 'FAILED' ? 0 : 5, targetValid: 50 }
      api.get.mockImplementation(async (path) => {
        if (path === '/api/osm-catalog/targets') return [targetReady({ latestRun: run })]
        return run
      })
      cleanup()
      renderCatalog()
      const label = status === 'PARTIAL' ? 'Parcial' : status === 'EXHAUSTED' ? 'Esgotado' : 'Falhou'
      await waitFor(() => expect(screen.getByText(label)).toBeInTheDocument())
      // Pool READY continua visível mesmo com run FAILED.
      expect(screen.getByText(/Pronto \(17\)/)).toBeInTheDocument()
      cleanup()
    }
  })

  it('active run desabilita botao', async () => {
    api.get.mockResolvedValue([targetReady({
      latestRun: { id: 40, targetId: 10, regionId: 2, status: 'RUNNING', qualifiedSaved: 3, targetValid: 50 }
    })])
    renderCatalog()
    await waitFor(() => expect(screen.getByRole('button', { name: /Sincronizando/ })).toBeDisabled())
  })

  it('erro anterior some ao iniciar novo run', async () => {
    api.get.mockResolvedValue([targetReady({
      lastError: 'Falha antiga',
      latestRun: { id: 41, targetId: 10, regionId: 2, status: 'FAILED', errorMessage: 'Falha antiga', targetValid: 50 }
    })])
    api.post.mockResolvedValue({ syncRunId: 42, targetId: 10, regionId: 2, status: 'QUEUED' })
    api.get.mockImplementation(async (path) => {
      if (path === '/api/osm-catalog/targets') {
        return [targetReady({
          lastError: 'Falha antiga',
          latestRun: { id: 41, targetId: 10, regionId: 2, status: 'FAILED', errorMessage: 'Falha antiga', targetValid: 50 }
        })]
      }
      return { id: 42, targetId: 10, regionId: 2, status: 'QUEUED', qualifiedSaved: 0, targetValid: 50 }
    })
    renderCatalog()
    await waitFor(() => expect(screen.getByText(/Falha antiga/)).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /Sincronizar Novamente/ }))
    await waitFor(() => expect(screen.queryByText(/Falha antiga/)).toBeNull())
  })

  it('409 reconcilia run ativo sem mensagem falsa de falha', async () => {
    api.get.mockResolvedValueOnce([targetReady()])
    const active = { id: 50, targetId: 10, regionId: 2, status: 'RUNNING', qualifiedSaved: 1, targetValid: 50 }
    const err409 = new Error('Já existe')
    err409.status = 409
    err409.code = 'OSM_SYNC_ALREADY_RUNNING'
    api.post.mockRejectedValueOnce(err409)
    api.get.mockImplementation(async (path) => {
      if (path === '/api/osm-catalog/targets') return [targetReady({ latestRun: active })]
      if (path === '/api/osm-catalog/sync/50') return active
      return null
    })
    renderCatalog()
    await waitFor(() => expect(screen.getByText('Sincronizar Novamente')).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /Sincronizar Novamente/ }))
    await waitFor(() => expect(screen.getByText('Sincronizando...')).toBeInTheDocument())
  })

  it('resposta perdida reconcilia sem segundo run', async () => {
    api.get.mockResolvedValueOnce([targetReady()])
    const created = { id: 60, targetId: 10, regionId: 2, status: 'QUEUED', qualifiedSaved: 0, targetValid: 50, createdAt: new Date().toISOString() }
    api.post.mockRejectedValueOnce(new TypeError('fetch failed'))
    api.get.mockImplementation(async (path) => {
      if (path === '/api/osm-catalog/targets') return [targetReady({ latestRun: created })]
      if (path === '/api/osm-catalog/sync/60') return created
      return null
    })
    renderCatalog()
    await waitFor(() => expect(screen.getByText('Sincronizar Novamente')).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /Sincronizar Novamente/ }))
    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(
      screen.getByRole('button', { name: /Na fila|Sincronizando/ })
    ).toBeInTheDocument())
  })

  it('unmount limpa timers de polling', async () => {
    api.get.mockResolvedValue([targetReady({
      latestRun: { id: 70, targetId: 10, regionId: 2, status: 'RUNNING', qualifiedSaved: 0, targetValid: 50 }
    })])
    api.get.mockImplementation(async (path) => {
      if (path === '/api/osm-catalog/targets') {
        return [targetReady({
          latestRun: { id: 70, targetId: 10, regionId: 2, status: 'RUNNING', qualifiedSaved: 0, targetValid: 50 }
        })]
      }
      return { id: 70, targetId: 10, regionId: 2, status: 'RUNNING', qualifiedSaved: 0, targetValid: 50 }
    })
    const { unmount } = renderCatalog()
    await waitFor(() => expect(api.get).toHaveBeenCalled())
    unmount()
    // Sem throw após unmount ao avançar timers.
    await vi.advanceTimersByTimeAsync(15000)
  })
})
