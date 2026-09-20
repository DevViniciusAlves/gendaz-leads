-- Gendaz Leads - V5: campaign city/country and lead email
-- Adiciona city e country nas campanhas (mantem location para compatibilidade)
-- Adiciona email nos leads

ALTER TABLE campaigns
    ADD COLUMN city VARCHAR(255),
    ADD COLUMN country VARCHAR(120);

-- Para campanhas existentes (legacy), city/country ficam NULL
-- O frontend deve usar fallback para location

ALTER TABLE leads
    ADD COLUMN email VARCHAR(320),
    ADD COLUMN normalized_email VARCHAR(320);

CREATE INDEX IF NOT EXISTS idx_leads_normalized_email ON leads(normalized_email);