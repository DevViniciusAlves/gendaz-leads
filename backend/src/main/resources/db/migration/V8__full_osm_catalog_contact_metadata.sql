-- V8__full_osm_catalog_contact_metadata.sql
-- Add contact metadata columns to osm_place_staging and osm_places
-- Add EXHAUSTED status to campaigns table

-- 1. Add contact metadata to osm_place_staging
ALTER TABLE osm_place_staging
    ADD COLUMN IF NOT EXISTS contact_status VARCHAR(50),
    ADD COLUMN IF NOT EXISTS contact_source VARCHAR(100),
    ADD COLUMN IF NOT EXISTS contact_source_url TEXT,
    ADD COLUMN IF NOT EXISTS enriched_at TIMESTAMPTZ;

-- 2. Add contact metadata to osm_places
ALTER TABLE osm_places
    ADD COLUMN IF NOT EXISTS contact_status VARCHAR(50),
    ADD COLUMN IF NOT EXISTS contact_source VARCHAR(100),
    ADD COLUMN IF NOT EXISTS contact_source_url TEXT,
    ADD COLUMN IF NOT EXISTS enriched_at TIMESTAMPTZ;

-- 3. Backfill contact_status for existing records in osm_places
UPDATE osm_places
SET contact_status = CASE
    WHEN phone IS NOT NULL AND BTRIM(phone) <> '' THEN 'LEGACY_PHONE'
    ELSE 'NO_PHONE'
END
WHERE contact_status IS NULL;

-- 4. Backfill contact_status for existing records in osm_place_staging
UPDATE osm_place_staging
SET contact_status = CASE
    WHEN phone IS NOT NULL AND BTRIM(phone) <> '' THEN 'LEGACY_PHONE'
    ELSE 'NO_PHONE'
END
WHERE contact_status IS NULL;

-- 5. Add EXHAUSTED status to campaigns table
-- First check and drop existing constraint
DO $$
DECLARE
    constraint_name text;
BEGIN
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'campaigns'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%status%';

    IF constraint_name IS NOT NULL THEN
        EXECUTE 'ALTER TABLE campaigns DROP CONSTRAINT ' || constraint_name;
    END IF;
END $$;

-- Add new constraint with EXHAUSTED status
ALTER TABLE campaigns
ADD CONSTRAINT chk_campaign_status
CHECK (
    status IN (
        'CREATED',
        'DISCOVERING',
        'ANALYZING',
        'GENERATING',
        'COMPLETED',
        'FAILED',
        'PARTIAL',
        'EXHAUSTED'
    )
);
