export function buildOsmSyncRequest({
  mode,
  regionId,
  city,
  country,
  niche,
}) {
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
