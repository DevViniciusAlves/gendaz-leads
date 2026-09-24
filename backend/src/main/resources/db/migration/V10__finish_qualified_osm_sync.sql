-- V10__finish_qualified_osm_sync.sql
-- Finish qualified OSM sync: staging carries the same qualified-pool
-- columns as osm_places so only fully qualified leads are published.
-- Never edits V7/V8/V9; additive only.

-- 1. Qualified pool columns on staging (mirrors osm_places from V9)
ALTER TABLE osm_place_staging
    ADD COLUMN IF NOT EXISTS normalized_phone VARCHAR(32),
    ADD COLUMN IF NOT EXISTS normalized_instagram VARCHAR(255),
    ADD COLUMN IF NOT EXISTS qualified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS qualified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS whatsapp_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS whatsapp_verified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS instagram_validated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS instagram_validated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS instagram_source VARCHAR(100),
    ADD COLUMN IF NOT EXISTS instagram_source_url TEXT,
    ADD COLUMN IF NOT EXISTS last_qualified_niche VARCHAR(255),
    ADD COLUMN IF NOT EXISTS contact_status VARCHAR(50),
    ADD COLUMN IF NOT EXISTS contact_source VARCHAR(100),
    ADD COLUMN IF NOT EXISTS contact_source_url TEXT,
    ADD COLUMN IF NOT EXISTS enriched_at TIMESTAMPTZ;

-- 2. Backfill staging contact_status for rows predating V8/V10 defaults
UPDATE osm_place_staging
SET contact_status = CASE
    WHEN phone IS NOT NULL AND BTRIM(phone) <> '' THEN 'LEGACY_PHONE'
    ELSE 'NO_PHONE'
END
WHERE contact_status IS NULL;

-- 3. Ensure sync-run counters exist (V9 created them; keep idempotent
-- for databases that skipped V9 ordering)
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

-- 4. Ensure PARTIAL/EXHAUSTED are accepted (V9 created the constraint;
-- re-apply idempotently without touching old migrations)
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

-- 5. Indexes for qualified staging publish
CREATE INDEX IF NOT EXISTS idx_osm_place_staging_qualified
    ON osm_place_staging(sync_run_id, qualified)
    WHERE qualified = TRUE;

CREATE INDEX IF NOT EXISTS idx_osm_place_staging_sync_run
    ON osm_place_staging(sync_run_id);
