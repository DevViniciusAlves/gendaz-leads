-- V14__osm_scan_state_java.sql
-- Endurece osm_candidate_scan_state para o runtime Java.
-- Outcomes validos do novo pipeline. Falha tecnica WPP nunca vira NOT_ON_WHATSAPP.

-- Garante colunas esperadas pelo batch Java (idempotente)
ALTER TABLE osm_candidate_scan_state
    ADD COLUMN IF NOT EXISTS target_id BIGINT REFERENCES osm_catalog_targets(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS evidence_type VARCHAR(50),
    ADD COLUMN IF NOT EXISTS source_url TEXT;

-- Backfill target_id quando houver correspondencia exata region+niche
UPDATE osm_candidate_scan_state s
SET target_id = t.id
FROM osm_catalog_targets t
WHERE s.target_id IS NULL
  AND s.region_id = t.region_id
  AND s.canonical_niche = t.canonical_niche;

CREATE INDEX IF NOT EXISTS idx_osm_scan_state_target
    ON osm_candidate_scan_state (target_id)
    WHERE target_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_osm_scan_state_retry
    ON osm_candidate_scan_state (retry_after)
    WHERE retry_after IS NOT NULL;

-- Restringe outcomes aos valores do produto (remove check antigo se existir)
DO $$
DECLARE
    cname text;
BEGIN
    SELECT conname INTO cname
    FROM pg_constraint
    WHERE conrelid = 'osm_candidate_scan_state'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%outcome%';
    IF cname IS NOT NULL THEN
        EXECUTE 'ALTER TABLE osm_candidate_scan_state DROP CONSTRAINT ' || quote_ident(cname);
    END IF;
END $$;

ALTER TABLE osm_candidate_scan_state
    ADD CONSTRAINT chk_scan_state_outcome CHECK (outcome IN (
        'NO_PHONE',
        'NO_INSTAGRAM',
        'NOT_ON_WHATSAPP',
        'NICHE_NOT_CONFIRMED',
        'DUPLICATE_SOURCE',
        'DUPLICATE_PHONE',
        'DUPLICATE_INSTAGRAM',
        'QUALIFIED'
    ));
