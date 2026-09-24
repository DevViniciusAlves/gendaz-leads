import { test } from 'node:test'
import assert from 'node:assert/strict'

import { buildOsmSyncRequest } from '../src/lib/osmSyncRequest.js'

test('EXISTING_REGION monta URL de regiao com body so de niche', () => {
  const req = buildOsmSyncRequest({
    mode: 'EXISTING_REGION',
    regionId: 2,
    niche: 'nail designer',
  })

  assert.equal(req.url, '/api/osm-catalog/regions/2/sync')
  assert.deepEqual(req.body, { niche: 'nail designer' })
})

test('EXISTING_REGION exige regionId e niche', () => {
  assert.throws(
    () => buildOsmSyncRequest({ mode: 'EXISTING_REGION', niche: 'nail designer' }),
    /regionId e niche obrigatórios/
  )
  assert.throws(
    () => buildOsmSyncRequest({ mode: 'EXISTING_REGION', regionId: 2 }),
    /regionId e niche obrigatórios/
  )
})

test('NEW_CITY monta /sync com city, country e niche', () => {
  const req = buildOsmSyncRequest({
    mode: 'NEW_CITY',
    city: 'Cuiabá',
    country: 'br',
    niche: 'barbearia',
  })

  assert.equal(req.url, '/api/osm-catalog/sync')
  assert.deepEqual(req.body, { city: 'Cuiabá', country: 'br', niche: 'barbearia' })
})

test('NEW_CITY exige city, country e niche', () => {
  assert.throws(
    () => buildOsmSyncRequest({ mode: 'NEW_CITY', country: 'br', niche: 'x' }),
    /city, country e niche obrigatórios/
  )
})

test('modo invalido lanca erro', () => {
  assert.throws(
    () => buildOsmSyncRequest({ mode: 'OUTRO', regionId: 2, niche: 'x' }),
    /sync mode inválido/
  )
  assert.throws(
    () => buildOsmSyncRequest({}),
    /sync mode inválido/
  )
})
