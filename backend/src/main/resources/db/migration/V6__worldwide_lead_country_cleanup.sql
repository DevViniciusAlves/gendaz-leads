-- Gendaz Leads V6
-- Ajustes para dedupe mundial e remoção do default implícito BR.

ALTER TABLE leads
    ALTER COLUMN country DROP DEFAULT;

ALTER TABLE leads
    DROP CONSTRAINT IF EXISTS uk_lead_name_location;

CREATE INDEX IF NOT EXISTS idx_leads_name_city_country
    ON leads (normalized_name, city, country);