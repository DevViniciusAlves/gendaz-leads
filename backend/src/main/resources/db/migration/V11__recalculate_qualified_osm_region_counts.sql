-- V11__recalculate_qualified_osm_region_counts.sql
-- Recalculate region place_count using only the qualified pool.
-- Legacy counts included non-qualified rows, which inflated the
-- "Leads qualificados" display. Additive only; never edits V7-V10.

UPDATE osm_catalog_regions r
SET
    place_count = (
        SELECT COUNT(*)
        FROM osm_places p
        WHERE p.region_id = r.id
          AND p.active = TRUE
          AND p.qualified = TRUE
          AND p.whatsapp_verified = TRUE
          AND p.instagram_validated = TRUE
    ),
    updated_at = NOW();
