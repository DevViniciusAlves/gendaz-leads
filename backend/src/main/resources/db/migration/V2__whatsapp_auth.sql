-- Gendaz Leads - WhatsApp auth state (Baileys) - V2
-- Payloads sao SEMPRE criptografados (AES-256-GCM) pelo whatsapp-service.
-- Nunca inspecionar o conteudo em claro por aqui.

CREATE TABLE whatsapp_auth_sessions (
    session_id VARCHAR(64) PRIMARY KEY,
    payload TEXT NOT NULL,
    registered BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE whatsapp_auth_keys (
    session_id VARCHAR(64) NOT NULL REFERENCES whatsapp_auth_sessions(session_id) ON DELETE CASCADE,
    key_type VARCHAR(64) NOT NULL,
    key_hash VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_whatsapp_auth_keys PRIMARY KEY (session_id, key_type, key_hash)
);

CREATE INDEX idx_whatsapp_auth_keys_session ON whatsapp_auth_keys(session_id);
