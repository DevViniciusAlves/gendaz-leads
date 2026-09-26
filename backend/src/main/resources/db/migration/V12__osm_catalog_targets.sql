-- V12__osm_catalog_targets.sql
-- Novo modelo: target = regiao + nicho. Resync one-click usa target persistido.
-- Nao edita V1..V11.

-- 1. Tabela de targets (cidade+nicho)
CREATE TABLE IF NOT EXISTS osm_catalog_targets (
    id BIGSERIAL PRIMARY KEY,
    region_id BIGINT NOT NULL REFERENCES osm_catalog_regions(id) ON DELETE CASCADE,
    requested_niche VARCHAR(255) NOT NULL,
    canonical_niche VARCHAR(255) NOT NULL,
    target_valid INT NOT NULL DEFAULT 50,
    qualified_count INT NOT NULL DEFAULT 0,
    available_new_count INT NOT NULL DEFAULT 0,
    pool_status VARCHAR(20) NOT NULL DEFAULT 'EMPTY',
    last_success_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_target_pool_status CHECK (pool_status IN ('EMPTY', 'READY', 'CONFIGURATION_REQUIRED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_osm_catalog_targets_region_niche
    ON osm_catalog_targets (region_id, canonical_niche);

CREATE INDEX IF NOT EXISTS idx_osm_catalog_targets_region
    ON osm_catalog_targets (region_id);

CREATE INDEX IF NOT EXISTS idx_osm_catalog_targets_pool
    ON osm_catalog_targets (pool_status);

-- 2. Backfill: um target por (region_id, canonical_niche) ja visto em runs
INSERT INTO osm_catalog_targets (region_id, requested_niche, canonical_niche, target_valid, pool_status)
SELECT DISTINCT ON (region_id, canonical_niche)
    region_id,
    COALESCE(NULLIF(TRIM(requested_niche), ''), COALESCE(canonical_niche, 'unknown')) AS requested_niche,
    COALESCE(NULLIF(TRIM(canonical_niche), ''), 'unknown') AS canonical_niche,
    COALESCE(NULLIF(target_valid, 0), 50) AS target_valid,
    'EMPTY' AS pool_status
FROM osm_sync_runs
WHERE region_id IS NOT NULL
  AND COALESCE(TRIM(COALESCE(canonical_niche, '')), '') <> ''
ON CONFLICT (region_id, canonical_niche) DO NOTHING;

-- 3. Colunas novas em osm_sync_runs: target FK + idempotency + contadores do novo pipeline
ALTER TABLE osm_sync_runs
    ADD COLUMN IF NOT EXISTS target_id BIGINT REFERENCES osm_catalog_targets(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS request_key VARCHAR(64),
    ADD COLUMN IF NOT EXISTS objects_read BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS commercial_candidates BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS potential_niche_candidates BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS niche_confirmed BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS direct_phone BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS recovered_phone BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS direct_instagram BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS recovered_instagram BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS whatsapp_checks BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS whatsapp_verified_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS discarded_niche BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS discarded_duplicate_source BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS discarded_duplicate_phone BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS discarded_duplicate_instagram BIGINT NOT NULL DEFAULT 0;

-- 4. Backfill target_id nos runs legados
UPDATE osm_sync_runs r
SET target_id = t.id
FROM osm_catalog_targets t
WHERE r.target_id IS NULL
  AND r.region_id = t.region_id
  AND COALESCE(r.canonical_niche, '') = t.canonical_niche;

-- 5. Idempotencia: mesma key retorna o mesmo run
CREATE UNIQUE INDEX IF NOT EXISTS uq_osm_sync_runs_request_key
    ON osm_sync_runs (request_key)
    WHERE request_key IS NOT NULL;

-- 6. Protecao: 1 run QUEUED/RUNNING por target (alem da protecao legada por regiao,
-- mantida de proposito: mesmo PBF/extract por cidade, serializa por regiao por seguranca).
-- Documentacao: serializacao por regiao e intencional (mesmo PBF Geofabrik/boundary).
CREATE UNIQUE INDEX IF NOT EXISTS uq_osm_sync_runs_active_target
    ON osm_sync_runs (target_id)
    WHERE status IN ('QUEUED', 'RUNNING') AND target_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_osm_sync_runs_target
    ON osm_sync_runs (target_id);

CREATE INDEX IF NOT EXISTS idx_osm_sync_runs_request_key_lookup
    ON osm_sync_runs (request_key)
    WHERE request_key IS NOT NULL;
