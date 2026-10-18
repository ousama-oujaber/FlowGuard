# FlowGuard Operational Runbook & Incident Response Guide

## 1. System Overview

FlowGuard provides reliable message ingestion, deterministic queue worker scheduling, bounded retries with jitter, lease fencing, and audited operator recovery.

All privileged operator actions produce immutable audit logs in `audit_event`.

---

## 2. Queue Health & Metrics Monitoring

### 2.1 Operational Summary Endpoint
Operators and monitoring agents can poll:
```bash
curl -H "X-Scope: operations:read" http://localhost:8080/api/v1/operations/summary
```
Example response:
```json
{
  "countsByStatus": {
    "QUEUED": 14,
    "PROCESSING": 2,
    "SUCCEEDED": 1420,
    "RETRY_SCHEDULED": 4,
    "DEAD_LETTERED": 3,
    "RECONCILIATION_REQUIRED": 1,
    "CANCELLED": 0
  },
  "totalMessages": 1444,
  "oldestQueuedAgeSeconds": 42,
  "generatedAt": "2026-10-18T01:40:00Z"
}
```

### 2.2 Alert Thresholds
- **Oldest Queue Age (`oldestQueuedAgeSeconds`):** Alert if `> 300` seconds (worker delay or downstream congestion).
- **Dead-Letter Count (`DEAD_LETTERED`):** Alert if rate exceeds 5 per minute (systemic bad payload or configuration issue).
- **Reconciliation Required (`RECONCILIATION_REQUIRED`):** Alert immediately (requires human operator investigation).

---

## 3. Failure Triage & Investigation

### 3.1 Inspecting Failed Message Details
```bash
curl -H "X-Client-Id: <clientId>" http://localhost:8080/api/v1/messages/<messageId>
```

### 3.2 Reviewing Execution Attempt History
```bash
curl -H "X-Client-Id: <clientId>" http://localhost:8080/api/v1/messages/<messageId>/attempts
```
Inspect:
- `errorCode` and `safeSummary`
- `targetStatusCode` (e.g. 504 Gateway Timeout, 422 Unprocessable Entity, 429 Rate Limit)
- `traceId` to correlate in downstream distributed traces

---

## 4. Remediation & Manual Re-drive Procedures

### 4.1 Retrying a Dead-Lettered Message
Once the underlying target service or reference data has been corrected:
```bash
curl -X POST http://localhost:8080/api/v1/messages/<messageId>/retries \
  -H "Content-Type: application/json" \
  -H "X-Actor-Id: ops-engineer-id" \
  -d '{
    "reason": "Target service database restored and confirmed ready to accept re-driven payroll."
  }'
```
Response: `202 Accepted`
```json
{
  "messageId": "9a0a8112-2591-4965-9831-2792c306d8a2",
  "status": "QUEUED",
  "auditEventId": "b1827471-efaa-4b02-86ec-35d2146f2aa1",
  "message": "Manual retry requested successfully; message re-queued for processing."
}
```

### 4.2 Handling Ambiguous Outcomes (`RECONCILIATION_REQUIRED`)
When an integration message enters `RECONCILIATION_REQUIRED`:
1. **Never retry blindly!** The remote target may have already committed the business side-effect before socket disconnection.
2. Query the target system's external inquiry/status API or database using `externalReference` or `correlationId`.
3. If the transaction **already occurred**: Mark the message resolved or record verification.
4. If the transaction **did not occur**: Trigger an audited manual retry command specifying the evidence in the `reason` field.

---

## 5. Worker Crash & Lease Recovery

FlowGuard automatically recovers crashed worker leases via `LeaseRecoveryScheduler`:
- Messages held in `PROCESSING` whose `lease_until` has passed are claimed via `SELECT ... FOR UPDATE SKIP LOCKED`.
- Active attempts are finalized as `UNKNOWN` (`LEASE_EXPIRED`).
- Messages are transitioned to `RECONCILIATION_REQUIRED` (or `DEAD_LETTERED` if attempts are exhausted).
- Stale workers that attempt to complete work after their lease expired are safely dropped by conditional lease fencing.
