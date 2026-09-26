-- V13__osm_place_niches.sql
-- Relacao N:N place <-> target. Substitui last_qualified_niche como verdade.
-- last_qualified_niche permanece apenas por compatibilidade, nunca como runtime.

CREATE TABLE IF NOT EXISTS osm_place_niches (
    id BIGSERIAL PRIMARY KEY,
    place_id BIGINT NOT NULL REFERENCES osm_places(id) ON DELETE CASCADE,
    target_id BIGINT NOT NULL REFERENCES osm_catalog_targets(id) ON DELETE CASCADE,
    canonical_niche VARCHAR(255) NOT NULL,
    niche_evidence_type VARCHAR(50) NOT NULL DEFAULT 'TAG_STRONG',
    niche_evidence_details TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    qualified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_place_niche_evidence CHECK (niche_evidence_type IN (
        'TAG_STRONG', 'OFFICIAL_TEXT', 'OFFICIAL_WEBSITE', 'OFFICIAL_SOCIAL_HUB', 'TAG_AND_TEXT'
    ))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_osm_place_niches_place_target
    ON osm_place_niches (place_id, target_id);

CREATE INDEX IF NOT EXISTS idx_osm_place_niches_target_active
    ON osm_place_niches (target_id, active);

CREATE INDEX IF NOT EXISTS idx_osm_place_niches_place
    ON osm_place_niches (place_id);

CREATE INDEX IF NOT EXISTS idx_osm_place_niches_niche
    ON osm_place_niches (canonical_niche);

-- Backfill: places qualificados legados ganham membership no target correspondente
-- via last_qualified_niche. Nao remove nada; apenas adiciona membership.
INSERT INTO osm_place_niches (place_id, target_id, canonical_niche, niche_evidence_type, niche_evidence_details, active, qualified_at, last_seen_at)
SELECT p.id AS place_id,
       t.id AS target_id,
       t.canonical_niche AS canonical_niche,
       'TAG_STRONG' AS niche_evidence_type,
       'backfill V13 from last_qualified_niche' AS niche_evidence_details,
       TRUE AS active,
       COALESCE(p.qualified_at, NOW()) AS qualified_at,
       NOW() AS last_seen_at
FROM osm_places p
JOIN osm_catalog_targets t
  ON t.region_id = p.region_id
 AND t.canonical_niche = p.last_qualified_niche
WHERE p.qualified = TRUE
  AND p.last_qualified_niche IS NOT NULL
  AND TRIM(p.last_qualified_niche) <> ''
ON CONFLICT (place_id, target_id) DO NOTHING;
