export function buildOsmSyncRequest({
  mode,
  regionId,
  targetId,
  city,
  country,
  niche,
}) {
  // Resync one-click: usa target persistido, sem modal, sem niche no body.
  if (mode === 'TARGET_RESYNC') {
    if (!targetId) {
      throw new Error('targetId obrigatório')
    }

    return {
      url: `/api/osm-catalog/targets/${targetId}/sync`,
      body: {},
    }
  }

  if (mode === 'EXISTING_REGION') {
    if (!regionId || !niche) {
      throw new Error('regionId e niche obrigatórios')
    }

    return {
      url: `/api/osm-catalog/regions/${regionId}/sync`,
      body: { niche },
    }
  }

  if (mode === 'NEW_CITY') {
    if (!city || !country || !niche) {
      throw new Error('city, country e niche obrigatórios')
    }

    return {
      url: '/api/osm-catalog/sync',
      body: { city, country, niche },
    }
  }

  throw new Error('sync mode inválido')
}

export function newIdempotencyKey() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}
