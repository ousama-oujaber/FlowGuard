-- FlowGuard Schema Migration: V2 Processing Attempt Table
-- Monotonic attempt history and outcome audit

CREATE TABLE IF NOT EXISTS processing_attempt (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL REFERENCES integration_message(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL CHECK (attempt_number >= 1),
    status VARCHAR(24) NOT NULL,
    worker_id VARCHAR(100) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT,
    error_code VARCHAR(80),
    error_category VARCHAR(40),
    retryable BOOLEAN,
    target_status_code INTEGER,
    trace_id VARCHAR(64),
    safe_summary VARCHAR(500),
    CONSTRAINT uq_message_attempt UNIQUE (message_id, attempt_number)
);

CREATE INDEX IF NOT EXISTS idx_processing_attempt_message 
ON processing_attempt (message_id, attempt_number);
