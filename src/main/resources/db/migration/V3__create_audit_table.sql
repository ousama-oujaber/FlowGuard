-- FlowGuard Schema Migration: V3 Operational Audit Event Table
-- Immutable audit log for privileged manual actions (retry, reconciliation)

CREATE TABLE IF NOT EXISTS audit_event (
    id UUID PRIMARY KEY,
    actor_id VARCHAR(150) NOT NULL,
    action VARCHAR(80) NOT NULL,
    message_id UUID REFERENCES integration_message(id) ON DELETE CASCADE,
    reason VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metadata JSONB
);

CREATE INDEX IF NOT EXISTS idx_audit_event_message 
ON audit_event (message_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_event_actor 
ON audit_event (actor_id, created_at DESC);
