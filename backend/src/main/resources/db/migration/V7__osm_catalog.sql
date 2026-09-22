-- V7__osm_catalog.sql
-- OSM Local Catalog tables

-- 1. osm_catalog_regions
CREATE TABLE osm_catalog_regions (
    id BIGSERIAL PRIMARY KEY,
    city VARCHAR(255) NOT NULL,
    normalized_city VARCHAR(255) NOT NULL,
    state VARCHAR(255),
    normalized_state VARCHAR(255),
    country VARCHAR(255) NOT NULL,
    country_code CHAR(2) NOT NULL,
    osm_type VARCHAR(10),
    osm_id BIGINT,
    geofabrik_region VARCHAR(100),
    catalog_status VARCHAR(20) NOT NULL DEFAULT 'EMPTY',
    last_success_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    place_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_catalog_status CHECK (catalog_status IN ('EMPTY', 'READY'))
);

CREATE UNIQUE INDEX uq_osm_catalog_regions_city_state_country
    ON osm_catalog_regions (normalized_city, normalized_state, country_code);

CREATE UNIQUE INDEX uq_osm_catalog_regions_osm_type_id
    ON osm_catalog_regions (osm_type, osm_id)
    WHERE osm_type IS NOT NULL AND osm_id IS NOT NULL;

CREATE INDEX idx_osm_catalog_regions_status ON osm_catalog_regions (catalog_status);
CREATE INDEX idx_osm_catalog_regions_country_code ON osm_catalog_regions (country_code);

-- 2. osm_sync_runs
CREATE TABLE osm_sync_runs (
    id BIGSERIAL PRIMARY KEY,
    region_id BIGINT NOT NULL REFERENCES osm_catalog_regions(id) ON DELETE CASCADE,
    requested_by_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    places_read BIGINT NOT NULL DEFAULT 0,
    places_staged BIGINT NOT NULL DEFAULT 0,
    places_inserted BIGINT NOT NULL DEFAULT 0,
    places_updated BIGINT NOT NULL DEFAULT 0,
    places_deactivated BIGINT NOT NULL DEFAULT 0,
    github_run_id BIGINT,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_sync_run_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCESS', 'FAILED'))
);

CREATE UNIQUE INDEX uq_osm_sync_runs_active
    ON osm_sync_runs (region_id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_osm_sync_runs_region ON osm_sync_runs (region_id);
CREATE INDEX idx_osm_sync_runs_status ON osm_sync_runs (status);
CREATE INDEX idx_osm_sync_runs_created_at ON osm_sync_runs (created_at);

-- 3. osm_place_staging
CREATE TABLE osm_place_staging (
    sync_run_id BIGINT NOT NULL REFERENCES osm_sync_runs(id) ON DELETE CASCADE,
    region_id BIGINT NOT NULL REFERENCES osm_catalog_regions(id) ON DELETE CASCADE,
    osm_type VARCHAR(10) NOT NULL,
    osm_id BIGINT NOT NULL,
    business_name VARCHAR(500),
    normalized_name VARCHAR(500),
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    address TEXT,
    city VARCHAR(255),
    state VARCHAR(255),
    country VARCHAR(255),
    country_code CHAR(2),
    phone VARCHAR(100),
    email VARCHAR(255),
    website VARCHAR(500),
    instagram VARCHAR(255),
    tags JSONB,
    source_timestamp TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (sync_run_id, osm_type, osm_id)
);

CREATE INDEX idx_osm_place_staging_region ON osm_place_staging (region_id);

-- 4. osm_places
CREATE TABLE osm_places (
    id BIGSERIAL PRIMARY KEY,
    region_id BIGINT NOT NULL REFERENCES osm_catalog_regions(id) ON DELETE CASCADE,
    osm_type VARCHAR(10) NOT NULL,
    osm_id BIGINT NOT NULL,
    business_name VARCHAR(500),
    normalized_name VARCHAR(500),
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    address TEXT,
    city VARCHAR(255),
    state VARCHAR(255),
    country VARCHAR(255),
    country_code CHAR(2),
    phone VARCHAR(100),
    email VARCHAR(255),
    website VARCHAR(500),
    instagram VARCHAR(255),
    tags JSONB,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source_timestamp TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_osm_places_osm_type_id UNIQUE (osm_type, osm_id)
);

CREATE INDEX idx_osm_places_region ON osm_places (region_id);
CREATE INDEX idx_osm_places_active ON osm_places (active);
CREATE INDEX idx_osm_places_normalized_name ON osm_places (normalized_name);
CREATE INDEX idx_osm_places_country_code ON osm_places (country_code);
CREATE INDEX idx_osm_places_tags ON osm_places USING GIN (tags);
CREATE INDEX idx_osm_places_region_active ON osm_places (region_id, active);