-- FlowGuard Schema Migration: V1 Initial Tables
-- Message system of record and idempotency registry

CREATE TABLE IF NOT EXISTS integration_message (
    id UUID PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    message_type VARCHAR(100) NOT NULL,
    source_system VARCHAR(100) NOT NULL,
    destination_key VARCHAR(100) NOT NULL,
    external_reference VARCHAR(200),
    correlation_id VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    payload_size_bytes INTEGER NOT NULL CHECK (payload_size_bytes >= 0),
    payload_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    next_attempt_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    last_error_summary VARCHAR(500),
    lease_owner VARCHAR(100),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);

-- Index for queue worker claims (FOR UPDATE SKIP LOCKED)
CREATE INDEX IF NOT EXISTS idx_integration_message_claim 
ON integration_message (status, next_attempt_at, created_at)
WHERE status IN ('QUEUED', 'RETRY_SCHEDULED');

-- Index for client-scoped listing and correlation lookup
CREATE INDEX IF NOT EXISTS idx_integration_message_client 
ON integration_message (client_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_integration_message_correlation 
ON integration_message (correlation_id);

-- Idempotency Registry Table
CREATE TABLE IF NOT EXISTS idempotency_record (
    client_id VARCHAR(100) NOT NULL,
    key_digest CHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    message_id UUID NOT NULL REFERENCES integration_message(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (client_id, key_digest)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_record_message 
ON idempotency_record (message_id);
