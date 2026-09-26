-- V15__osm_scan_state_pipeline_version.sql
-- Adiciona pipeline_version ao scan state para invalidar cache quando algoritmo muda.
-- Adiciona outcome NO_OFFICIAL_CONTACT_CHANNEL para fast reject.

-- pipeline_version: versao do pipeline de enrichment (ex: java-osm-v2)
ALTER TABLE osm_candidate_scan_state
    ADD COLUMN IF NOT EXISTS pipeline_version VARCHAR(50);

-- Index para queries por pipeline_version
CREATE INDEX IF NOT EXISTS idx_osm_scan_state_pipeline
    ON osm_candidate_scan_state (pipeline_version)
    WHERE pipeline_version IS NOT NULL;

-- Atualiza constraint de outcomes para incluir NO_OFFICIAL_CONTACT_CHANNEL
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
        'QUALIFIED',
        'NO_OFFICIAL_CONTACT_CHANNEL'
    ));