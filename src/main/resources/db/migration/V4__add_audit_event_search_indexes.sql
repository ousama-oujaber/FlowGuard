-- FlowGuard Schema Migration: V4 Audit Event Search Indexes
-- Optimize filtering by action and chronological sorting for audit export

CREATE INDEX IF NOT EXISTS idx_audit_event_action
ON audit_event (action, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_event_created_at
ON audit_event (created_at DESC);
