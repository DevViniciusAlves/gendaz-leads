-- V9__qualified_osm_lead_pool.sql
-- Qualified OSM lead pool

-- 1. Add qualified pool columns to osm_places
ALTER TABLE osm_places
    ADD COLUMN IF NOT EXISTS qualified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS qualified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS normalized_phone VARCHAR(32),
    ADD COLUMN IF NOT EXISTS normalized_instagram VARCHAR(255),
    ADD COLUMN IF NOT EXISTS whatsapp_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS whatsapp_verified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS instagram_validated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS instagram_validated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS instagram_source VARCHAR(100),
    ADD COLUMN IF NOT EXISTS instagram_source_url TEXT,
    ADD COLUMN IF NOT EXISTS last_qualified_niche VARCHAR(255);

-- 2. Add sync metadata columns to osm_sync_runs
ALTER TABLE osm_sync_runs
    ADD COLUMN IF NOT EXISTS requested_niche VARCHAR(255),
    ADD COLUMN IF NOT EXISTS canonical_niche VARCHAR(255),
    ADD COLUMN IF NOT EXISTS niche_strategy_json TEXT,
    ADD COLUMN IF NOT EXISTS target_valid INT NOT NULL DEFAULT 50,
    ADD COLUMN IF NOT EXISTS candidates_scanned BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS niche_matches BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS discarded_no_phone BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS discarded_no_instagram BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS discarded_not_on_whatsapp BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS discarded_duplicate BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS technical_failures BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS qualified_saved BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS dataset_exhausted BOOLEAN NOT NULL DEFAULT FALSE;

-- 3. Update sync run status constraint to include PARTIAL and EXHAUSTED
DO $$
DECLARE
    constraint_name text;
BEGIN
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'osm_sync_runs'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%status%';

    IF constraint_name IS NOT NULL THEN
        EXECUTE 'ALTER TABLE osm_sync_runs DROP CONSTRAINT ' || constraint_name;
    END IF;
END $$;

ALTER TABLE osm_sync_runs
ADD CONSTRAINT chk_sync_run_status
CHECK (
    status IN (
        'QUEUED',
        'RUNNING',
        'SUCCESS',
        'FAILED',
        'PARTIAL',
        'EXHAUSTED'
    )
);

-- 4. Create candidate scan state table for checkpoint/resume
CREATE TABLE IF NOT EXISTS osm_candidate_scan_state (
    id BIGSERIAL PRIMARY KEY,
    region_id BIGINT NOT NULL
        REFERENCES osm_catalog_regions(id) ON DELETE CASCADE,
    canonical_niche VARCHAR(255) NOT NULL,
    osm_type VARCHAR(10) NOT NULL,
    osm_id BIGINT NOT NULL,
    source_timestamp TIMESTAMPTZ,
    outcome VARCHAR(50) NOT NULL,
    normalized_phone VARCHAR(32),
    normalized_instagram VARCHAR(255),
    last_checked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    retry_after TIMESTAMPTZ,
    details TEXT,
    CONSTRAINT uq_osm_candidate_scan_state
        UNIQUE(region_id, canonical_niche, osm_type, osm_id)
);

CREATE INDEX IF NOT EXISTS idx_osm_candidate_scan_state_lookup
    ON osm_candidate_scan_state(region_id, canonical_niche, outcome);

-- 5. Add indexes for qualified pool queries
CREATE INDEX IF NOT EXISTS idx_osm_places_qualified_pool
    ON osm_places(region_id, qualified, active);

CREATE INDEX IF NOT EXISTS idx_osm_places_qualified_phone
    ON osm_places(normalized_phone)
    WHERE qualified = TRUE;

CREATE INDEX IF NOT EXISTS idx_osm_places_qualified_instagram
    ON osm_places(normalized_instagram)
    WHERE qualified = TRUE;

CREATE INDEX IF NOT EXISTS idx_osm_places_whatsapp_verified
    ON osm_places(region_id, whatsapp_verified)
    WHERE qualified = TRUE;

-- 6. Backfill: existing records with phone get qualified=false (legacy)
-- They must be re-qualified through new sync process
UPDATE osm_places
SET qualified = FALSE,
    whatsapp_verified = FALSE,
    instagram_validated = FALSE
WHERE qualified IS NULL OR qualified = FALSE;