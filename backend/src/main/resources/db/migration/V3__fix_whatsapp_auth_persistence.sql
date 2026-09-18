-- Gendaz Leads - WhatsApp auth persistence fix - V3
-- Remove dangerous FK de whatsapp_auth_keys.session_id -> whatsapp_auth_sessions.session_id
-- Permite que Signal Keys sejam persistidos antes da sessao principal existir (fluxo de pareamento).
-- Preserva tabelas, dados, criptografia e session_id isoladamente.

DO $$
DECLARE
  fk_name TEXT;
BEGIN
  -- Localiza e remove a FK se existir (nome pode variar por versao do PG)
  SELECT conname INTO fk_name
  FROM pg_constraint
  WHERE conname LIKE 'whatsapp_auth_keys_session_id_fk%'
    OR conname LIKE 'fk_%whatsapp_auth_keys%';

  IF fk_name IS NOT NULL THEN
    EXECUTE format('ALTER TABLE whatsapp_auth_keys DROP CONSTRAINT %I;', fk_name);
    RAISE NOTICE 'FK removida: %', fk_name;
  ELSE
    RAISE NOTICE 'Nenhuma FK encontrada para remover em whatsapp_auth_keys.';
  END IF;
END$$;

-- Garante que indexes existem isoladamente (nao dependem de FK)
CREATE INDEX IF NOT EXISTS idx_whatsapp_auth_keys_session ON whatsapp_auth_keys(session_id);
COMMENT ON INDEX idx_whatsapp_auth_keys_session IS 'Indexador isolado; FK removida com seguranca V3';

-- Garante que tabelas existem e isoladas (idempotente)
CREATE TABLE IF NOT EXISTS whatsapp_auth_sessions (
    session_id VARCHAR(64) PRIMARY KEY,
    payload TEXT NOT NULL,
    registered BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS whatsapp_auth_keys (
    session_id VARCHAR(64) NOT NULL,
    key_type VARCHAR(64) NOT NULL,
    key_hash VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_whatsapp_auth_keys PRIMARY KEY (session_id, key_type, key_hash)
);

COMMENT ON TABLE whatsapp_auth_keys IS 'Signal Keys e outros tipos do Baileys; persistente isoladamente desde V3.';

-- View resumida para debug (nao expor creds em claro)
CREATE OR REPLACE VIEW whatsapp_auth_sessions_summary AS
SELECT session_id, registered, created_at, updated_at
FROM whatsapp_auth_sessions;