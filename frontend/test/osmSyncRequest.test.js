import { describe, it, expect } from 'vitest'

import { buildOsmSyncRequest, newIdempotencyKey } from '../src/lib/osmSyncRequest.js'

describe('buildOsmSyncRequest', () => {
  it('TARGET_RESYNC monta URL de target sem pedir niche', () => {
    const req = buildOsmSyncRequest({ mode: 'TARGET_RESYNC', targetId: 10 })
    expect(req.url).toBe('/api/osm-catalog/targets/10/sync')
    expect(req.body).toEqual({})
  })

  it('TARGET_RESYNC exige targetId', () => {
    expect(() => buildOsmSyncRequest({ mode: 'TARGET_RESYNC' })).toThrow(/targetId/)
  })

  it('EXISTING_REGION legado monta URL de regiao com body so de niche', () => {
    const req = buildOsmSyncRequest({ mode: 'EXISTING_REGION', regionId: 2, niche: 'nail designer' })
    expect(req.url).toBe('/api/osm-catalog/regions/2/sync')
    expect(req.body).toEqual({ niche: 'nail designer' })
  })

  it('NEW_CITY monta /sync com city, country e niche', () => {
    const req = buildOsmSyncRequest({ mode: 'NEW_CITY', city: 'Cuiabá', country: 'br', niche: 'barbearia' })
    expect(req.url).toBe('/api/osm-catalog/sync')
    expect(req.body).toEqual({ city: 'Cuiabá', country: 'br', niche: 'barbearia' })
  })

  it('modo invalido lanca erro', () => {
    expect(() => buildOsmSyncRequest({ mode: 'OUTRO' })).toThrow(/sync mode inválido/)
  })

  it('newIdempotencyKey gera UUID unico por intencao', () => {
    const a = newIdempotencyKey()
    const b = newIdempotencyKey()
    expect(a).toBeTruthy()
    expect(b).toBeTruthy()
    expect(a).not.toBe(b)
  })
})
