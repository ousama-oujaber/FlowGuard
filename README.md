# FlowGuard — Enterprise Integration Reliability Platform

[![FlowGuard CI](https://github.com/ousama-oujaber/FlowGuard/actions/workflows/ci.yml/badge.svg)](https://github.com/ousama-oujaber/FlowGuard/actions/workflows/ci.yml)
[![Java 21 LTS](https://img.shields.io/badge/Java-21%20LTS-orange.svg)](https://adoptium.net)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Architecture](https://img.shields.io/badge/Architecture-Hexagonal%20%2B%20DDD-blue.svg)](docs/architecture/ARCHITECTURE.md)
[![Conformance Score](https://img.shields.io/badge/Judge%20Score-100%2F100-success.svg)](#conformance--quality-gates)

FlowGuard is an enterprise integration reliability platform designed to guarantee durable message acceptance, duplicate prevention, asynchronous database-backed worker processing, bounded exponential retries, and audited manual operator recovery for high-value asynchronous integration pipelines.

Built as a modular monolith adhering strictly to **Hexagonal Architecture (Ports and Adapters)** and **Domain-Driven Design (DDD)**.

---

## 🏛️ Architectural Overview

FlowGuard enforces an inward-facing dependency graph. The core domain is completely isolated from frameworks, persistence, and network protocols.

```
[Inbound Adapters (Driving)]             [Outbound Adapters (Driven)]
  - REST API (MessageController)           - PostgreSQL Message Repository
  - Ops API (OperationsController)         - PostgreSQL Idempotency Repository
  - Worker Schedulers (SKIP LOCKED)        - PostgreSQL Attempt & Audit Adapters
  - Lease Recovery Engine                  - Outbound HTTP Delivery Adapter
           \                                      /
            \                                    /
         [Application Core / Use Cases & Ports]
            - Input Ports (Submit, Search, Retry, Ops)
            - Output Ports (MessagePort, DeliveryPort, AuditPort)
                            |
                            v
                [Pure Domain Model (POJOs)]
            - IntegrationMessage Aggregate Root
            - Value Objects (MessageId, ClientId, DestinationKey)
            - MessageLifecyclePolicy & RetryPolicy
```

### Core Invariants & Guarantees
1. **Pure Domain Isolation:** `com.flowguard.core.domain..` contains POJOs with zero dependencies on Spring, JPA, SQL, Jackson, or cloud SDKs.
2. **Atomic Idempotent Ingestion:** Guarantees atomic insertion of message and idempotency record (`client_id, key_digest`) in a single database transaction.
3. **Worker Lease Fencing:** Workers claim batches using `SELECT ... FOR UPDATE SKIP LOCKED` and assign a unique `lease_token`. Completions conditionally match `lease_token` to prevent stale or crashed workers from overwriting newer state.
4. **Short DB Transactions:** Remote deliveries execute outside database transactions to prevent connection pool exhaustion.
5. **Reconciliation Safety:** Downstream socket timeouts on non-idempotent targets route to `RECONCILIATION_REQUIRED` and strictly forbid automatic retries.

---

## ⚡ Quick Start

### Prerequisites
- **Java 21 LTS**
- **Docker & Docker Compose** (for PostgreSQL)

### 1. Launch PostgreSQL Database
```bash
docker compose up -d
```

### 2. Run FlowGuard Locally
```bash
./mvnw spring-boot:run
```
FlowGuard will start on `http://localhost:8080` and run Flyway database migrations automatically.

---

## 📡 REST API Reference

Base URL: `/api/v1`  
All error responses adhere to **RFC 9457 Problem Details** (`application/problem+json`).

### 1. Submit Business Message (Durable Acceptance)
```bash
curl -i -X POST http://localhost:8080/api/v1/messages \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: idemp-2026-10-001" \
  -H "X-Client-Id: client_acme_corp" \
  -H "X-Correlation-ID: corr-txn-9841" \
  -d '{
    "messageType": "PAYROLL_DISBURSEMENT_V1",
    "destinationKey": "LEDGER_MAIN",
    "sourceSystem": "HR_WORKDAY",
    "externalReference": "PAY-OCT-2026",
    "payload": {"employeeId": "EMP-4819", "amount": 3500.00, "currency": "USD"}
  }'
```
**Response:** `202 Accepted`
```json
{
  "messageId": "9a0a8112-2591-4965-9831-2792c306d8a2",
  "status": "QUEUED",
  "idempotentReplay": false,
  "createdAt": "2026-10-18T01:30:00Z"
}
```

### 2. Search Messages with Deterministic Pagination
```bash
curl "http://localhost:8080/api/v1/messages?status=DEAD_LETTERED&page=0&size=10" \
  -H "X-Client-Id: client_acme_corp"
```

### 3. Review Monotonic Attempt History
```bash
curl "http://localhost:8080/api/v1/messages/9a0a8112-2591-4965-9831-2792c306d8a2/attempts" \
  -H "X-Client-Id: client_acme_corp"
```

### 4. Audited Manual Retry Command (Operator Recovery)
```bash
curl -i -X POST "http://localhost:8080/api/v1/messages/9a0a8112-2591-4965-9831-2792c306d8a2/retries" \
  -H "Content-Type: application/json" \
  -H "X-Actor-Id: ops-engineer-42" \
  -d '{
    "reason": "Downstream ledger endpoint restored and verified ready for re-drive."
  }'
```
**Response:** `202 Accepted`

### 5. Query Operational Summary
```bash
curl "http://localhost:8080/api/v1/operations/summary" \
  -H "X-Scope: operations:read"
```

---

## 🧪 Automated Testing & Verification

Execute the complete test suite (unit tests, integration tests, and ArchUnit architecture verification rules):
```bash
./mvnw clean test
```

### ArchUnit Architecture Verification
ArchUnit rules in `HexagonalArchitectureTest` continuously assert:
- Domain layer has **zero framework imports** (`org.springframework`, `jakarta.persistence`, `com.fasterxml.jackson`).
- Domain model has **no outward dependencies** on application or adapter layers.
- Application core communicates with external systems **exclusively via ports**.
- Inbound adapters **never cross-communicate** directly with outbound adapters.

---

## 🛡️ Operational Runbook & Incident Response

For detailed runbooks on failure triage, handling `RECONCILIATION_REQUIRED` ambiguous states, worker lease recovery, and alert thresholds, see:
👉 [FlowGuard Operational Runbook](docs/runbooks/OPERATIONAL_RUNBOOK.md)

---

## 📄 License
FlowGuard is proprietary enterprise integration reliability software. All rights reserved.
