---
title: "FlowGuard — Enterprise Integration Reliability Platform"
subtitle: "Product, Functional, Domain, Architecture, API, Security and Operational Specification"
author: "Ousama Oujaber"
date: "17 October 2026"
version: "1.0"
status: "Draft for internal technical review"
---

# Contents

- [Document Control](#document-control)
- [1. Executive Summary](#1-executive-summary)
  - [1.1 Outcomes](#11-outcomes)
  - [1.2 Success Measures](#12-success-measures)
- [2. Context and Problem Statement](#2-context-and-problem-statement)
- [3. Goals, Non-Goals and Boundaries](#3-goals-non-goals-and-boundaries)
- [4. Users, Stakeholders and Use Cases](#4-users-stakeholders-and-use-cases)
- [5. Scope and Release Definition](#5-scope-and-release-definition)
- [6. Architecture Principles](#6-architecture-principles)
- [7. Logical Architecture](#7-logical-architecture)
- [8. Message Lifecycle and Failure Semantics](#8-message-lifecycle-and-failure-semantics)
- [9. Processing, Retry, Idempotency and Concurrency](#9-processing-retry-idempotency-and-concurrency)
- [10. Data Model and Persistence Design](#10-data-model-and-persistence-design)
- [11. REST API Contract](#11-rest-api-contract)
- [12. Security, Privacy and Compliance](#12-security-privacy-and-compliance)
- [13. Observability and Operations](#13-observability-and-operations)
- [14. Configuration and Deployment](#14-configuration-and-deployment)
- [15. Technology Baseline and Dependency Policy](#15-technology-baseline-and-dependency-policy)
- [16. Quality Attributes and Proposed Non-Functional Requirements](#16-quality-attributes-and-proposed-non-functional-requirements)
- [17. Testing Strategy](#17-testing-strategy)
- [18. CI/CD, Release and Change Management](#18-cicd-release-and-change-management)
- [19. Acceptance Criteria and Definition of Done](#19-acceptance-criteria-and-definition-of-done)
- [20. Risks, Mitigations and Open Decisions](#20-risks-mitigations-and-open-decisions)
- [21. Repository and Documentation Deliverables](#21-repository-and-documentation-deliverables)
- [22. Glossary](#22-glossary)
- [23. External References](#23-external-references)
- [Appendix A. Reference Architecture in One View](#appendix-a-reference-architecture-in-one-view)
- [Appendix B. Minimum ADR Set](#appendix-b-minimum-adr-set)
- [Appendix C. Proposal Guardrails](#appendix-c-proposal-guardrails)

# Document Control

| Field | Value |
|---|---|
| Document title | FlowGuard — Enterprise Integration Reliability Platform |
| Document ID | FG-SPEC-001 |
| Version | 1.0 |
| Status | Draft for internal technical review; not production approval |
| Prepared by | Ousama Oujaber |
| Date | 17 October 2026 |
| Intended audience | Engineering, integration architecture, application owners, operations, security and business stakeholders |
| Scope | Target-state specification for an integration reliability service; initial MVP and production-readiness gates |
| Source-code audit | Not performed: a repository snapshot was not supplied with this request |

## Revision History

| Version | Date | Change |
|---|---|---|
| 0.1 | 17 October 2026 | Initial concept and architecture outline |
| 1.0 | 17 October 2026 | Expanded enterprise proposal covering domain design, interfaces, reliability, security, tests, operations and acceptance criteria |

## Reading Guide

This specification is written to support a technical review and a staged implementation. Normative terms **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT** and **MAY** are used in their ordinary standards sense: MUST/MUST NOT are mandatory for the target design unless an approved deviation is documented; SHOULD/SHOULD NOT allow a justified exception. Requirements labelled **PROPOSED** are starting points for review and are not contractual SLAs until accepted by the responsible stakeholders.

> **Important implementation note:** the proposal author reports that an initial FlowGuard project already uses Hexagonal Architecture and Domain-Driven Design (DDD). This document preserves those principles as the architectural baseline. Because the actual repository, build file, migrations and tests were not supplied for inspection, this document does not certify that the current implementation satisfies every requirement below. Before changing code, complete the implementation inventory in the companion `FlowGuard_Implementation_and_Governance_Plan.md` and record deviations as Architecture Decision Records (ADRs).

---

# 1. Executive Summary

FlowGuard is a proposed integration reliability and message-operations service for environments where business applications exchange messages through APIs or integration flows. It provides a consistent way to accept messages, validate them, persist them durably, coordinate processing, record every attempt, apply a controlled retry policy, identify terminal failures, and allow authorized operators to investigate and recover messages.

The design deliberately starts as a **modular monolith** with **Hexagonal Architecture** and **DDD**. This gives the project explicit domain boundaries and testable ports without introducing distributed infrastructure before it is justified. PostgreSQL is the durable system of record and, for the initial release, can also support a database-backed work queue. A pluggable delivery/processing port isolates the domain and application core from the concrete target system. The MVP uses a deterministic simulator; production connectors are introduced only after target protocols, credentials, network rules, error semantics and ownership are approved.

The target is not to reproduce SAP Integration Suite or replace an enterprise integration platform. It is to demonstrate a focused reliability layer and a reference implementation for message ingestion, lifecycle control, duplicate protection, retry and operational visibility. It may later be evaluated as an internal tool, an integration-service component or a technical proof of concept, subject to company architecture, security, operational and business approval.

## 1.1 Outcomes

1. A caller can submit a JSON message and receive a durable message identifier.
2. The service records message lifecycle changes and processing attempts with correlation identifiers.
3. Transient failures can be retried under a bounded policy; permanent failures can be routed to a dead-letter state.
4. Repeated submissions with the same idempotency key and same request fingerprint resolve to the original message, while reuse with different content is rejected.
5. Operators with appropriate permissions can inspect message metadata, attempt history and safe error details, and request an audited retry where allowed.
6. Developers can run the application and database locally, execute automated tests, and build the project through CI.
7. The architecture remains independent of a particular connector or cloud vendor.

## 1.2 Success Measures

The MVP is successful when the acceptance criteria in Section 19 pass in an automated or demonstrable test environment. Production suitability is a separate decision: it requires approved SLOs, a threat model, data classification, retention rules, operational ownership, backup/restore evidence, load testing, security review and target-connector sign-off.

# 2. Context and Problem Statement

Enterprise integrations commonly involve multiple systems, message formats and operational owners. When a transfer fails, the business and support teams need to know whether a message was accepted, what processing step failed, whether retrying is safe, how many attempts were made, and who initiated a recovery action. If each integration implements these concerns differently, investigation becomes slow and duplicate or lost business operations become more likely.

FlowGuard addresses this set of concerns through a consistent message lifecycle and operational contract. It is designed around the following illustrative scenario:

- A source application submits an invoice, employee-payroll record or other business message through an authenticated REST endpoint.
- FlowGuard validates the envelope, derives caller identity from authentication, calculates an idempotency fingerprint and commits the message to durable storage.
- A worker claims the message and invokes a registered processing/delivery adapter.
- The adapter returns success, a retryable failure, a permanent failure or an ambiguous outcome.
- FlowGuard persists the outcome and makes the next action observable.
- Authorized support staff can search for the message by identifier, correlation ID, source system, status or time range and can request a retry when domain policy permits it.

The example payloads in this specification are synthetic. Real business payloads, real customer identifiers, credentials, tenant URLs, production logs and internal company configuration MUST NOT be copied into a public repository or demonstration environment.

# 3. Goals, Non-Goals and Boundaries

## 3.1 Goals

- Durable acceptance of bounded JSON messages.
- Explicit message lifecycle and domain invariants.
- Duplicate-submission protection scoped to the authenticated caller.
- Asynchronous processing with bounded concurrency and crash recovery.
- Retry policy for classified transient errors.
- Dead-letter and reconciliation states for terminal or ambiguous outcomes.
- Search and detailed message/attempt inspection.
- Audited manual recovery actions.
- Structured logs, metrics, health endpoints and correlation/tracing support.
- Stable REST contract documented with OpenAPI.
- Hexagonal architecture boundaries and DDD domain model that are unit-testable without Spring or a database.
- Repeatable local environment and automated CI.

## 3.2 Non-Goals for the MVP

- Replacing SAP Integration Suite, SAP Cloud Integration, an enterprise service bus or an API gateway.
- Guaranteeing “exactly once” execution across an arbitrary remote system.
- Providing a graphical administration portal or Angular frontend.
- Supporting arbitrary user-supplied target URLs.
- Storing or managing production credentials in the application database.
- Implementing multi-region active-active processing.
- Providing unlimited message size, arbitrary binary attachments or long-term document storage.
- Shipping production SAP connectors without target API selection and security approval.
- Implementing a general-purpose workflow engine, event-sourcing framework or microservice fleet.
- Claiming regulatory compliance without an organization-specific assessment.

## 3.3 Delivery Boundaries

The initial deployment is one application and one PostgreSQL database. A worker may run in the same process but MUST interact with the application core through use cases/ports rather than bypassing domain rules. Horizontal scaling is allowed only after queue claiming, leases, locking and idempotency are implemented and verified. The MVP delivery adapter is a deterministic simulator; live HTTP/SAP/ERP connectors are separately scoped extensions.

# 4. Users, Stakeholders and Use Cases

## 4.1 Personas

| Persona | Need | Authorized capabilities (proposed) |
|---|---|---|
| Source system / integration client | Submit a business message and correlate the outcome | Submit only allowed message types and destinations; read only records allowed to the client |
| Support operator | Investigate a failed or delayed message | Search and inspect operational fields; view safe error details |
| Integration operator | Retry a failed message after checking the cause | Retry only eligible messages; provide a reason; view attempt history |
| Operations/SRE | Understand service health and capacity | Read aggregate metrics and health; no payload access by default |
| Security administrator | Manage service identities and access policy | Configure identities/scopes through approved identity infrastructure |
| Application owner | Define message schema and target behavior | Approve message type, connector and error classification |
| Platform administrator | Deploy, back up, restore and monitor | Operate infrastructure using separate least-privilege access |

Final roles and scopes MUST be mapped to the organization's identity and access model before production. The table is a proposed minimum, not a claim that every role already exists.

## 4.2 Primary Use Cases

**UC-01 — Submit a message.** An authenticated client submits a valid JSON message with an idempotency key. FlowGuard validates authorization, enforces request limits, persists the message and idempotency record atomically, then responds with the message ID and initial state.

**UC-02 — Safely repeat a submission.** A client repeats the same request with the same idempotency key. FlowGuard returns the existing message reference. If the key is associated with a different canonical request fingerprint, FlowGuard returns a conflict and does not create another message.

**UC-03 — Process a queued message.** A worker claims a ready message under a short database transaction, creates an attempt record, and calls the application delivery port outside the database transaction. On completion, a new short transaction records the result if the worker still owns the valid lease.

**UC-04 — Retry a transient failure.** A failure classifier identifies a retryable failure. FlowGuard schedules the next attempt according to a bounded backoff policy. It does not hold a database transaction open while waiting for the backoff period.

**UC-05 — Dead-letter a message.** A message is dead-lettered when a non-retryable failure occurs, the retry limit is reached, or policy explicitly directs the message to terminal review.

**UC-06 — Record an ambiguous outcome.** If a remote connector times out after sending a request and the target does not support idempotency or authoritative status lookup, the result may be unknown. FlowGuard marks the message `RECONCILIATION_REQUIRED` rather than blindly repeating a potentially non-idempotent business operation.

**UC-07 — Investigate a message.** An authorized operator retrieves the safe message envelope, current status, attempt history, correlation ID and normalized error details. Payload access is permission-controlled and may be masked or disabled by policy.

**UC-08 — Request manual retry.** An authorized operator submits a retry request with a reason and an idempotency key for the retry command. FlowGuard validates the current status and error classification, creates an audit event, and queues the next attempt only if retry is allowed.

**UC-09 — Recover after a worker crash.** Expired processing leases are found by a recovery job. The system classifies the prior outcome as safe to retry or ambiguous based on connector capabilities and policy. It never silently assumes a remote side effect did not occur.

# 5. Scope and Release Definition

## 5.1 MVP — Required

- One versioned REST API for message submit/read/search/attempts/manual retry.
- JSON payloads up to a configurable default limit (proposed initial default: 256 KiB).
- Configured message types and logical destinations; callers cannot submit a raw URL as a destination.
- PostgreSQL persistence and versioned database migrations.
- Message aggregate and lifecycle invariants.
- Idempotency on message submission and manual retry requests.
- DB-backed queue with bounded worker concurrency, claim locking and time-bound leases.
- Deterministic simulator adapter for success, transient failure, permanent failure and ambiguous timeout scenarios.
- Bounded retry policy with maximum attempts, backoff, jitter and error classification.
- Dead-letter and reconciliation states.
- Search/filter endpoints with pagination and deterministic sorting.
- Audited operational actions and safe, structured logging.
- Authentication and authorization suitable for the deployment mode; production auth must integrate with the approved identity provider.
- Health/readiness, metrics and correlation/tracing foundations.
- Unit, persistence, API contract and end-to-end tests.
- Docker Compose local environment; CI build/test workflow; OpenAPI documentation; runbook and README.

## 5.2 Post-MVP Candidates

- Real allowlisted HTTP target connector.
- SAP-specific receiver adapter using a company-approved API/protocol and identity model.
- OAuth2/OIDC client credentials integration with the organization identity provider if not already present.
- Outbox and event publication when another system genuinely requires change notifications.
- Angular monitoring UI.
- External broker (e.g. a managed queue) if measured throughput, retention, ordering or operational requirements justify the new component.
- Tenant/business-unit partitioning if required by the target operating model.
- Schema registry or versioned schema bundles for multiple message types.
- Bulk ingestion with explicit batch limits and per-item result semantics.
- Advanced alerting, retention automation and dashboards.

## 5.3 Explicitly Deferred

Distributed saga orchestration, event sourcing, multi-region failover, arbitrary dynamic connector configuration, unrestricted webhook forwarding and automatic retries for destinations with unknown side-effect semantics are deferred until justified by signed requirements and ADRs.

# 6. Architecture Principles

1. **Domain rules stay in the domain.** Lifecycle rules and invariants MUST not be enforced only by controllers, ORM callbacks or UI behavior.
2. **Dependencies point inward.** Domain code MUST NOT depend on Spring, HTTP, JPA, PostgreSQL types, logging implementation or cloud SDKs.
3. **Use cases define application behavior.** Inbound adapters invoke input ports; application services coordinate domain behavior and outbound ports.
4. **Infrastructure is replaceable.** Persistence, scheduling, telemetry and target connectivity live behind adapter/port boundaries.
5. **Transactions are short.** No database transaction is held open while calling a remote target or sleeping for retry backoff.
6. **Durability precedes acknowledgment.** A successful `202 Accepted` response is returned only after the message and idempotency record commit.
7. **At-least-once execution must be handled honestly.** Exactly-once delivery is not promised. Duplicate safety requires idempotency on both ingress and any target with side effects.
8. **Secure defaults.** Least privilege, bounded payloads, safe error messages, secret-manager integration and restricted egress are preferred.
9. **Operational behavior is a product feature.** Each failure class, retry decision and terminal state must be observable and documented.
10. **Modular monolith first.** Split into separately deployed services only when a measured constraint or ownership boundary supports the added complexity.

# 7. Logical Architecture

## 7.1 High-Level Request and Processing Flow

1. REST adapter receives the request and extracts the authenticated principal plus request/trace metadata.
2. Application input port validates the command, allowed message type, logical destination and caller permission.
3. Domain factory creates a valid `IntegrationMessage`; the idempotency policy computes a request fingerprint.
4. Persistence adapter commits the message and idempotency record in one database transaction, protected by uniqueness constraints.
5. API adapter returns `202 Accepted` with a message ID and a `Location` link.
6. Worker adapter claims a due message using a short transaction and creates a processing attempt plus lease.
7. Application use case routes through `DeliveryPort` to the registered connector/simulator.
8. The outcome classifier maps the adapter result to success, retryable failure, permanent failure or unknown outcome.
9. A short transaction closes the attempt and updates message state only if the lease token/version is still current.
10. Monitoring queries expose status and history without exposing secrets or unrestricted payload data.

## 7.2 Hexagonal Architecture

| Layer | Responsibility | Allowed dependencies | Must not do |
|---|---|---|---|
| Domain | Entities, aggregates, value objects, domain services, state transitions and domain errors | Java language and stable domain abstractions | Import Spring, JPA, HTTP, database or cloud types |
| Application | Use cases, orchestration, input/output ports, transaction boundaries and application DTOs | Domain and declared ports | Contain web-specific DTOs or vendor-specific persistence logic |
| Inbound adapters | REST controllers, scheduler/worker trigger, CLI/test adapters | Application input ports; web framework | Implement domain policy directly |
| Outbound adapters | PostgreSQL persistence, simulator, future target connectors, telemetry integrations | Application output ports and external libraries | Be invoked directly from domain code |
| Bootstrap/configuration | Dependency wiring, profile setup and security/filter configuration | All outer layers for assembly only | Become a second location for business rules |

## 7.3 Proposed Bounded Contexts / Modules

**Message Management.** Owns message identity, submission contract, lifecycle state, idempotency association and safe message metadata.

**Processing Orchestration.** Owns claiming, processing attempt coordination, lease behavior, timeout recovery and result persistence. It calls a port; it does not know the vendor or wire protocol.

**Delivery.** Owns connector selection by a registered logical destination, connector capabilities, delivery result normalization and destination-specific idempotency support. In the MVP this contains only a simulation adapter.

**Operations and Audit.** Owns operational queries, attempt history, manual retry authorization, audit records and aggregate status views. It MUST not bypass the message aggregate to force status changes.

**Identity/Security integration.** Owns mapping from authenticated client identity to capabilities. The actual identity provider and token validation belong in the security adapter/configuration, not in domain entities.

A module is not automatically a separate deployable service. For the MVP these modules reside in one application and communicate through typed application interfaces.

## 7.4 DDD Model

### Aggregate Root: `IntegrationMessage`

The aggregate root represents one accepted integration message and guards its lifecycle invariants. It contains or references:

- `MessageId` (UUID)
- authenticated `ClientId` / submission scope
- `MessageType` (registered identifier)
- `SourceSystem` (resolved from trusted client configuration or validated against it)
- `DestinationKey` (registered logical destination, never an arbitrary URL)
- `CorrelationId`
- optional `ExternalReference`
- bounded JSON payload or payload reference
- payload size and request fingerprint
- current status, attempt count, max attempts and `nextAttemptAt`
- last normalized failure code and safe summary
- creation/update/terminal timestamps
- persistence version or another concurrency token

### Processing Attempt

An append-oriented record for one execution attempt. It records attempt number, start/end times, normalized outcome, duration, retryability, connector/result code and safe failure summary. Detailed request/response bodies and secrets are not retained by default. Attempt history is operational evidence and MUST not be silently overwritten by a subsequent retry.

### Idempotency Record

A unique mapping from authenticated client scope plus an idempotency-key digest to a request fingerprint and message ID. It is created atomically with the message. It prevents duplicate accepted messages during the configured idempotency retention window.

### Audit Event

An append-only record of privileged operational actions, including actor, action, message ID, timestamp, reason and carefully allowlisted metadata. It does not contain the full payload, authorization headers, tokens or secrets.

### Value Objects

`MessageId`, `ClientId`, `MessageType`, `DestinationKey`, `CorrelationId`, `IdempotencyKey`, `PayloadFingerprint`, `RetryPolicy`, `FailureCode`, `SafeErrorSummary`, `LeaseToken` and `ProcessingAttemptNumber`. Constructors/factories enforce format and size constraints.

### Domain Services / Policies

- `MessageLifecyclePolicy`: allowed status transitions.
- `RetryDecisionPolicy`: error category, attempt count, deadline and retry eligibility.
- `IdempotencyPolicy`: same key/same request versus same key/different request behavior.
- `DestinationPolicy`: only registered destination keys can be selected.
- `RecoveryPolicy`: expired lease outcome classification.

### Domain Events (optional internal use)

`MessageAccepted`, `ProcessingStarted`, `ProcessingSucceeded`, `RetryScheduled`, `MessageDeadLettered`, `ReconciliationRequired` and `ManualRetryRequested`. The MVP does not require event sourcing or an external event bus. Domain events may support in-process decoupling/audit generation; persistence/publication guarantees must be designed explicitly before adding external subscribers.

## 7.5 Aggregate Invariants

- A message MUST have one stable identifier and a registered message type and destination.
- A message cannot transition directly from a terminal state to `PROCESSING` without a valid, authorized recovery command.
- Only one active lease is considered valid for a message at a time; result updates from an expired or superseded lease are rejected.
- Attempt numbers are unique per message and monotonically increase.
- A successful terminal message is not reprocessed by ordinary retry endpoints.
- The same idempotency key within the same client scope and retention window cannot create two accepted messages.
- A key reused with different semantic request content returns a conflict and creates no second message.
- Retry must obey message state, error classification, max attempts, operator permission and audit requirements.
- An ambiguous side-effect outcome must not be retried blindly unless the destination's idempotency/status-query capability makes the retry safe.
- Every response that acknowledges durable acceptance must correspond to a committed message and idempotency record.

# 8. Message Lifecycle and Failure Semantics

## 8.1 Status Definitions

| Status | Meaning | Typical next state |
|---|---|---|
| `QUEUED` | Persisted and ready for processing | `PROCESSING` |
| `PROCESSING` | Claimed by a worker with a valid lease | `SUCCEEDED`, `RETRY_SCHEDULED`, `DEAD_LETTERED`, `RECONCILIATION_REQUIRED` |
| `RETRY_SCHEDULED` | Retryable failure recorded; next attempt is due in the future | `PROCESSING` when due, or `DEAD_LETTERED` if policy changes/retry budget expires |
| `SUCCEEDED` | Processing and target outcome confirmed successful | Terminal; no ordinary retry |
| `DEAD_LETTERED` | Permanent failure or retry budget exhausted; operator review required | Terminal unless an approved manual retry policy permits requeue |
| `RECONCILIATION_REQUIRED` | Outcome may have occurred but cannot be determined safely | Requires a reconciliation decision; no automatic retry by default |
| `CANCELLED` | Cancelled before a side-effecting operation starts, where cancellation is supported | Terminal |

`RECEIVED` may exist as an internal transient domain state during acceptance, but the externally visible response should normally report `QUEUED` once the transaction commits. Avoid persisting a long-lived `RECEIVED` state unless it has a meaningful operational distinction. State naming in the actual code should be reconciled in the initial implementation inventory.

## 8.2 Transition Rules

- `QUEUED -> PROCESSING` only through a successful worker claim and lease creation.
- `PROCESSING -> SUCCEEDED` only after the adapter provides a success outcome and the owning lease is still valid.
- `PROCESSING -> RETRY_SCHEDULED` only for a retryable failure and while retries remain.
- `PROCESSING -> DEAD_LETTERED` for a permanent failure or exhausted retry budget.
- `PROCESSING -> RECONCILIATION_REQUIRED` for an ambiguous outcome that cannot be made safe through destination idempotency or status inquiry.
- `RETRY_SCHEDULED -> PROCESSING` only when due and successfully claimed.
- Manual retry MUST be a command with an authorized actor, an explicit reason and an audit record. It must not be implemented as an unrestricted `UPDATE status = 'QUEUED'` endpoint.
- `SUCCEEDED` and `CANCELLED` are terminal. Reprocessing successful business work requires a distinct business-level decision and must not be disguised as a technical retry.

## 8.3 Error Taxonomy

| Category | Example | Default handling |
|---|---|---|
| Validation / contract | Missing required field, unknown message type | Reject before durable acceptance with a client error; do not enqueue |
| Authentication / authorization | Missing scope, forbidden destination | Reject request; security audit as policy requires |
| Transient transport | Connection reset before request sent, temporary name-resolution failure | Retry if connector can establish that no side effect occurred or target idempotency makes it safe |
| Remote transient response | HTTP 429 or selected 5xx from a future connector | Retry under `Retry-After` and bounded policy; classify by connector contract |
| Permanent business rejection | Target rejects a reference or state as invalid | Dead-letter; correct business data before an explicitly authorized recovery |
| Configuration failure | Missing target config or invalid secret reference | Usually pause/dead-letter or open circuit and alert; do not retry every message aggressively |
| Timeout / unknown side effect | Request may have reached target but response is absent | `RECONCILIATION_REQUIRED` unless destination idempotency or status lookup resolves ambiguity |
| Internal unexpected error | Unhandled code path | Record sanitized error and trace; bounded retry only if classified safe; alert after threshold |

Error classification is owned by the connector plus domain policy. HTTP status code alone is not sufficient to determine whether a business operation is safe to retry.

# 9. Processing, Retry, Idempotency and Concurrency

## 9.1 Acceptance Transaction

The submit use case MUST atomically persist the message and its idempotency record. If either write fails, both are rolled back and the API must not return a successful acceptance response. A unique constraint is the final protection against concurrent duplicate requests. Application-level pre-checks alone are insufficient because two callers can race.

## 9.2 Request Fingerprint

The request fingerprint represents the semantic submission command, including message type, registered destination, allowed source identity, external business reference where meaningful, and normalized JSON payload. It excludes request-specific tracing headers and transport metadata that do not change the business command. JSON object keys must be canonicalized consistently before hashing; the canonicalization algorithm and version must be documented and covered by tests. SHA-256 is suitable for a deterministic fingerprint; store only the digest, not a duplicate of unnecessary sensitive content.

The idempotency key should be bounded (proposed: 1–128 printable ASCII characters), treated as an opaque token and scoped to the authenticated client. Store a digest of the key if practical. The retention interval is configurable and must be agreed with application owners; it must be at least as long as the source's legitimate redelivery window. Expiring the key while the corresponding business operation can still be resent may permit duplicates.

## 9.3 Claiming Work

A worker claims a small batch in a short database transaction. PostgreSQL's row-locking `FOR UPDATE SKIP LOCKED` is a suitable initial mechanism for queue-like workloads, provided the query, isolation assumptions and concurrency tests are reviewed. The transaction selects due `QUEUED`/`RETRY_SCHEDULED` rows, locks them, assigns a unique lease token/worker ID and lease expiry, increments/opens an attempt, then commits.

The delivery call happens outside the claim transaction. On completion, the result update must use a conditional write tied to the current lease token and/or optimistic version. A stale worker must not overwrite the outcome of a newer worker. A lease duration must exceed normal processing time or be renewed while work remains active. Worker crashes are detected by expiry and recovered according to the recovery policy.

## 9.4 Retry Policy

- Retry must be bounded by a configurable maximum attempt count and, optionally, an overall retry deadline.
- Backoff should be exponential with a cap and random jitter to avoid synchronized retry storms.
- Honor valid destination `Retry-After` values where appropriate, but cap them to policy and ensure date parsing is safe.
- Do not retry client validation, authentication or authorization failures.
- Do not retry permanent business rejections automatically.
- Circuit breaking / throttling may be added for actual remote connectors; do not introduce a circuit breaker for the simulator just for appearance.
- Retry policy changes should be versioned or captured at attempt time for post-incident explanation.
- A manual retry is rate-limited and audited; an operator cannot exceed safety policy without a higher-authority override process explicitly approved by the organization.

Starter values for local demonstration only (not production policy): maximum attempts 5; initial backoff 5 seconds; multiplier 2; maximum backoff 10 minutes; jitter enabled. These values MUST be reviewed against business SLAs and target throttling before live traffic.

## 9.5 Delivery Guarantee

FlowGuard provides durable acceptance plus **at-least-once processing attempts** under retries/recovery. It does not claim exactly-once execution across external systems. To make duplicate delivery safe, a future target adapter must pass a stable FlowGuard message/business idempotency identifier and the remote system must honor it, or provide a reliable status inquiry/reconciliation operation. If neither is available and a timeout leaves the outcome ambiguous, use `RECONCILIATION_REQUIRED` instead of automatic retry.

## 9.6 Cancellation and Message Expiration

Cancellation may be permitted only before a delivery side effect begins. Once a target request is in flight, cancellation cannot guarantee that the remote system stops processing. Optional time-to-live/expiry rules must be defined by message type; expired queued messages can be dead-lettered or cancelled with an explicit reason. Never silently drop an accepted message because it is old.

# 10. Data Model and Persistence Design

The following is the proposed logical model. Physical schema names and types must be reconciled with existing migrations before implementation. PostgreSQL `jsonb` is suitable for bounded JSON payloads and queryable metadata, but must not become a reason to store arbitrary sensitive documents or build unbounded full-text search into the first release.

## 10.1 `integration_message`

| Column | Proposed type | Constraints / meaning |
|---|---|---|
| `id` | UUID | Primary key; generated by application or database consistently |
| `client_id` | VARCHAR(100) | Required authenticated caller scope |
| `message_type` | VARCHAR(100) | Required registered type identifier |
| `source_system` | VARCHAR(100) | Derived from or validated against authenticated client config |
| `destination_key` | VARCHAR(100) | Required logical destination; never a raw URL |
| `external_reference` | VARCHAR(200) | Optional business reference; no unrestricted indexing without need |
| `correlation_id` | VARCHAR(100) | Required/generated for tracing; bounded and sanitized |
| `payload` | JSONB | Bounded business payload; protection/retention required |
| `payload_size_bytes` | INTEGER | Size measured from normalized incoming request/payload policy |
| `payload_fingerprint` | CHAR(64) | Digest for semantic request identity; algorithm/version documented |
| `status` | VARCHAR(32) | Controlled enum/state values enforced in domain and persistence |
| `attempt_count` | INTEGER | Non-negative; increments atomically with an attempt start |
| `max_attempts` | INTEGER | Positive policy snapshot or referenced policy version |
| `next_attempt_at` | TIMESTAMPTZ | Nullable; set for scheduled retry |
| `last_error_code` | VARCHAR(80) | Normalized code, no secret/raw stack trace |
| `last_error_summary` | VARCHAR(500) | Safe operator-facing summary |
| `lease_owner` | VARCHAR(100) | Worker identity for current claim; nullable when not processing |
| `lease_token` | UUID | Lease fencing token, nullable when not processing |
| `lease_until` | TIMESTAMPTZ | Lease expiration; nullable when not processing |
| `created_at` | TIMESTAMPTZ | UTC timestamp |
| `updated_at` | TIMESTAMPTZ | UTC timestamp |
| `completed_at` | TIMESTAMPTZ | UTC timestamp for terminal outcomes; nullable otherwise |
| `version` | BIGINT | Optimistic version if supported by persistence approach |

Recommended checks include non-negative attempt count and payload size, valid status values, and consistent lease fields. Avoid business logic encoded only in database triggers; keep domain invariants in the core and use constraints as defense in depth.

## 10.2 `idempotency_record`

| Column | Proposed type | Meaning |
|---|---|---|
| `client_id` | VARCHAR(100) | Scope derived from authenticated principal |
| `key_digest` | CHAR(64) | Digest of the opaque idempotency key |
| `request_fingerprint` | CHAR(64) | Semantic request digest |
| `message_id` | UUID | Foreign key to accepted message |
| `created_at` | TIMESTAMPTZ | First accepted time |
| `expires_at` | TIMESTAMPTZ | Retention cutoff; policy-controlled |

Primary/unique key: `(client_id, key_digest)`. Use a single transactional insertion path. On uniqueness conflict, load the existing record and compare the request fingerprint; return the existing message for a match or `409 IDEMPOTENCY_KEY_REUSED` for a mismatch. Do not rely on a pre-insert select for concurrency safety.

## 10.3 `processing_attempt`

| Column | Proposed type | Meaning |
|---|---|---|
| `id` | UUID | Primary key |
| `message_id` | UUID | FK to integration message |
| `attempt_number` | INTEGER | Unique per message; starts at 1 |
| `status` | VARCHAR(24) | `RUNNING`, `SUCCEEDED`, `FAILED`, `UNKNOWN` |
| `worker_id` | VARCHAR(100) | Worker that claimed the attempt |
| `started_at` / `completed_at` | TIMESTAMPTZ | Start/end time |
| `duration_ms` | BIGINT | Derived duration, nullable until complete |
| `error_code` | VARCHAR(80) | Normalized error code |
| `error_category` | VARCHAR(40) | Transient, permanent, configuration, unknown, internal |
| `retryable` | BOOLEAN | Policy decision snapshot; not a universal promise |
| `target_status_code` | INTEGER | Optional connector response status; sanitized |
| `trace_id` | VARCHAR(64) | Trace correlation if available |
| `safe_summary` | VARCHAR(500) | Redacted diagnostic summary |

Constraints: unique `(message_id, attempt_number)`, foreign key with deletion/retention rules explicitly chosen, and no update of completed historical attempts other than narrowly approved redaction/retention processing. The MVP may update a `RUNNING` attempt to its final outcome; finalized attempts are append-oriented.

## 10.4 `audit_event`

| Column | Proposed type | Meaning |
|---|---|---|
| `id` | UUID | Primary key |
| `actor_id` | VARCHAR(150) | Authenticated human/service identity; no token values |
| `action` | VARCHAR(80) | Controlled action such as `MESSAGE_RETRY_REQUESTED` |
| `message_id` | UUID, nullable | Affected message if applicable |
| `reason` | VARCHAR(1000) | Mandatory for privileged manual retry/reconciliation decisions |
| `created_at` | TIMESTAMPTZ | UTC timestamp |
| `metadata` | JSONB | Allowlisted non-sensitive attributes only |

Audit retention, access, export and immutability expectations must be confirmed with security/operations. An audit event must not be writable through public client APIs.

## 10.5 Index Strategy

Initial candidates to validate with query plans and representative volume:

- `integration_message(status, next_attempt_at, created_at)` for worker claims and due retries.
- `integration_message(client_id, created_at DESC)` for scoped message listing.
- `integration_message(correlation_id)` for exact correlation lookup.
- `integration_message(message_type, created_at DESC)` where message-type filtering is common.
- `processing_attempt(message_id, attempt_number)` unique index.
- `idempotency_record(client_id, key_digest)` unique index.
- `audit_event(message_id, created_at DESC)` for operational history.

Indexes increase write cost and storage; add only those supported by endpoint needs and observed query plans. Use deterministic sort keys with a unique tiebreaker for pagination.

## 10.6 Transaction Boundaries

- Submit: insert message + idempotency record in one transaction.
- Claim: lock/claim message + create attempt + establish lease in one short transaction.
- Complete attempt: update attempt and message state atomically after connector call, conditional on the valid lease token/version.
- Manual retry: validate status + transition + add audit event in one transaction.
- Retention: delete/anonymize eligible data in bounded batches; do not delete data still referenced by retention-required audit records without an approved policy.

# 11. REST API Contract

## 11.1 API Conventions

- Base path: `/api/v1`.
- Content type: `application/json`; error responses use `application/problem+json` following RFC 9457 where toolchain compatibility permits.
- Authentication: production authentication through an approved identity provider; OAuth2/OIDC client credentials is the preferred service-to-service approach. A static local-development API key MAY be used only under a development profile and MUST NOT be accepted in production.
- Caller identity/scope comes from the validated principal, not an untrusted `clientId` property supplied in JSON.
- All timestamps are UTC ISO 8601/RFC 3339 values.
- IDs are UUIDs. Unknown IDs return a safe 404 within the caller's authorized scope, avoiding information leaks.
- Requests are size-bounded and validated before persistence.
- The OpenAPI contract MUST be versioned and checked in with the code; breaking changes require a versioning decision.

## 11.2 Submit Message

`POST /api/v1/messages`

Headers:

- `Authorization: Bearer <token>` in production or a dev-only auth mechanism.
- `Idempotency-Key: <opaque-client-key>` required for safe submission retries.
- `X-Correlation-ID: <opaque-id>` optional; generated by the service when absent.
- W3C `traceparent` / `tracestate` may be accepted for trace propagation after strict validation.

Example request:

```json
{
  "messageType": "PAYROLL_ENTRY_V1",
  "destinationKey": "DEMO_LEDGER",
  "externalReference": "PAYROLL-DEMO-2026-001",
  "payload": {
    "employeeReference": "EMP-DEMO-001",
    "amount": 1250.00,
    "currency": "MAD",
    "postingDate": "2026-10-17"
  }
}
```

The sample is synthetic and must not be replaced with real employee or financial records in public demonstrations.

Successful first acceptance: `202 Accepted` after durable commit.

```json
{
  "messageId": "6d6a6e91-2b3d-4b4e-8c8b-2d6b2b31e107",
  "status": "QUEUED",
  "correlationId": "corr-demo-8a11",
  "acceptedAt": "2026-10-17T18:00:00Z",
  "links": {
    "self": "/api/v1/messages/6d6a6e91-2b3d-4b4e-8c8b-2d6b2b31e107",
    "attempts": "/api/v1/messages/6d6a6e91-2b3d-4b4e-8c8b-2d6b2b31e107/attempts"
  }
}
```

Repeated same key and same fingerprint: return the original message reference (proposed response `200 OK` with `idempotentReplay: true`, or `202 Accepted` for a consistent API contract—select one behavior in OpenAPI and test it). Same key with changed content: `409 Conflict`, code `IDEMPOTENCY_KEY_REUSED`. Missing required idempotency key: `400 Bad Request`. Invalid envelope: `400` or `422` per the final contract; do not mix both semantics without documentation.

## 11.3 Get Message

`GET /api/v1/messages/{messageId}`

Returns the current safe envelope: ID, message type, registered source/destination labels, correlation ID, status, attempt count, timestamps, last normalized error, and links. Payload fields are returned only if the caller has explicit payload-read scope and policy allows payload disclosure. Default response should avoid returning the entire payload for operational queries.

## 11.4 Search Messages

`GET /api/v1/messages?status=&messageType=&sourceSystem=&destinationKey=&correlationId=&createdFrom=&createdTo=&page=&size=&sort=`

Requirements:

- Queries are always scoped by authenticated client/tenant permissions.
- `page` is non-negative; default `size` is 20; proposed max size is 100.
- Allowed sort fields are enumerated (for example `createdAt`, `updatedAt`, `status`); arbitrary SQL property sorting is forbidden.
- Add a unique tiebreaker (normally message ID) for deterministic sorting.
- Date ranges must be bounded; reject malformed or overly broad ranges according to configured query policy.
- Use page-number pagination for the MVP only if measured volume supports it; keyset pagination is preferred for very large datasets or deep paging.

## 11.5 Get Attempts

`GET /api/v1/messages/{messageId}/attempts`

Returns an ordered, paginated list of attempt number, status, start/end time, duration, normalized error category/code, retryable decision, target result code where safe, and trace ID. It does not return authorization headers, full target request bodies or secret values.

## 11.6 Request Manual Retry

`POST /api/v1/messages/{messageId}/retries`

Headers: authentication, `Idempotency-Key` for the retry command, optional correlation/trace headers.

Example body:

```json
{
  "reason": "Target configuration was corrected and the message is confirmed safe to retry."
}
```

Proposed result: `202 Accepted` with a retry request ID/message status, or `409 Conflict` when current state/policy disallows the action. The operation MUST verify the actor has `messages:retry`, validate retry eligibility, persist an audit event and transition/queue state atomically. Do not expose an unrestricted endpoint that can force any message status.

## 11.7 Operational Summary

`GET /api/v1/operations/summary`

Returns aggregate counts (queued, processing, retry scheduled, dead-lettered, reconciliation required), approximate oldest queue age and time-windowed failure counts. This endpoint requires an operational read scope, is rate-limited and must avoid leaking cross-client message details. High-cardinality identifiers MUST NOT become metric labels.

## 11.8 Health and Metrics

Expose only the minimum required management endpoints. Liveness checks whether the process is alive; readiness checks whether critical dependencies allow it to serve. Database health details, environment variables, configuration values and full stack traces MUST NOT be public. Metrics are exposed to the approved internal scraping/telemetry system, not the public internet.

## 11.9 Standard Error Envelope

Use RFC 9457 Problem Details where supported:

```json
{
  "type": "https://errors.example.invalid/idempotency-key-reused",
  "title": "Idempotency key reused with a different request",
  "status": 409,
  "detail": "The supplied key is already associated with a different request.",
  "instance": "/api/v1/messages",
  "code": "IDEMPOTENCY_KEY_REUSED",
  "correlationId": "corr-demo-8a11"
}
```

The example domain is illustrative and must be replaced by the approved documentation domain or stable URN before publication. Do not expose stack traces, SQL errors, credentials, hostnames or remote payloads in client-facing errors.

## 11.10 Endpoint Authorization Matrix

| Endpoint family | Scope / role (proposed) | Required scope of data |
|---|---|---|
| Submit message | `messages:write` | Caller may use only allowed message type and destination |
| Get one message | `messages:read` | Message belongs to authorized client/tenant scope |
| Search messages | `messages:read` or `operations:read` | Filters and results remain within authorized scope |
| View payload | `messages:payload:read` | Separate least-privilege capability; may be disabled |
| View attempts | `messages:read` | Same message authorization as parent message |
| Retry message | `messages:retry` | Privileged actor, eligible state, reason required |
| Summary/metrics | `operations:read` | Aggregate or operations scope; avoid cross-scope leakage |
| Health | Platform/operator policy | Only minimal health state publicly reachable, if exposed at all |

# 12. Security, Privacy and Compliance

## 12.1 Authentication and Authorization

- Production requests MUST authenticate using the company's approved identity provider and token validation rules.
- Prefer OAuth2 client credentials or the approved workload identity method for system-to-system traffic.
- Authorization MUST be checked for every object lookup and every privileged function; possession of a UUID is not authorization.
- A client identity is derived from trusted token claims/mapped configuration, not accepted from a JSON body as an authority source.
- Define separate read, payload-read, retry and operations scopes.
- Use short-lived credentials where supported, rotation procedures and secret-manager references.
- Do not create custom cryptography or invent a token format.

## 12.2 Data Protection

- Use TLS for all network communication in deployed environments.
- Use platform/database encryption at rest where supported and required by policy.
- Classify payloads before any real data is accepted. The default public demo uses synthetic data only.
- Do not log raw payloads, access tokens, cookies, API keys, passwords, connection strings or full remote response bodies.
- Mask or omit fields classified as personal, financial, authentication or otherwise sensitive.
- Payload-read permission should be separate from metadata-read permission.
- Retention and deletion must cover the message payload, idempotency mapping, attempt records, indexes, backups and exported logs consistently.
- The organization must confirm applicable privacy, data-residency and records-retention requirements. This document does not assert GDPR, local-law or other regulatory compliance.

## 12.3 SSRF and Destination Controls

The request contract accepts only a logical `destinationKey`. It MUST NOT accept arbitrary URLs from callers. Destination endpoints, when introduced, are configured by trusted administrators or deployment configuration, validated against explicit host/port/protocol allowlists, and protected by outbound network controls. DNS/IP resolution and redirect behavior must be considered so an approved hostname cannot be used to reach metadata services or private administrative endpoints. Redirects should be disabled by default or revalidated at every hop. The web connector must not fetch caller-controlled URLs.

## 12.4 Input and Resource Protection

- Enforce request-body size before reading the entire body into memory.
- Validate content type, JSON structure, required fields, identifier length, allowed enums and date/decimal formats.
- Bound query range, page size, worker batch size, retry count and processing timeout.
- Rate-limit by client and endpoint at the API gateway or application layer as appropriate.
- Use database statement/connection timeouts and connection pool limits.
- Apply back-pressure when queue age or depth crosses approved thresholds.
- Avoid regular expressions or payload-processing rules vulnerable to catastrophic complexity.

## 12.5 Database and Secrets

- Runtime DB role MUST have only required schema privileges; migration privileges should be separate where feasible.
- Secrets MUST be supplied through the approved secret store or protected runtime injection, not committed to source control.
- `.env` examples must contain only placeholders, never working credentials.
- Rotate credentials without code changes where possible.
- Restrict production Actuator/management endpoints and inspect exposure in an automated security test.

## 12.6 Audit

Manual retries, reconciliations, policy changes and administrative connector changes must produce attributable audit entries. Audit records must be access-controlled, retained under approved policy and protected against ordinary application users' modification. If stronger tamper evidence is required, agree on an external centralized immutable log/service; do not claim local append-only tables are tamper-proof against a database administrator.

## 12.7 Security Validation Checklist

- Broken object-level authorization: attempt to read/retry another client's message.
- Broken authentication: missing, expired, malformed and wrong-audience token.
- Property-level authorization: payload suppression without payload-read scope.
- Function-level authorization: caller with read scope attempts retry.
- Unrestricted resource consumption: oversized body, deep page, high submit rate.
- SSRF: destination URL injection, redirect to private IP, DNS rebinding tests for the future HTTP connector.
- Security misconfiguration: default credentials, verbose errors, public management endpoint, wildcard CORS.
- Unsafe outbound consumption: malformed remote response, timeout, huge response, redirect and invalid TLS.

Use the OWASP API Security Top 10 (2023) as an awareness checklist, then perform a company-approved threat model and security review for the actual deployment.

# 13. Observability and Operations

## 13.1 Structured Logging

Emit machine-readable structured logs (JSON in deployed profiles) with at least timestamp, severity, service name/version, environment, trace ID, correlation ID, message ID where safe, client scope where policy permits, operation name and normalized error code. Do not include raw payloads or secrets. Human-readable safe summaries are allowed; stack traces are for protected internal logs and should be sampled/rate-limited to avoid log floods.

## 13.2 Metrics

Recommended low-cardinality metrics:

- `flowguard_messages_accepted_total` by message type and outcome.
- `flowguard_processing_attempts_total` by normalized outcome/error category.
- `flowguard_queue_depth` by status.
- `flowguard_oldest_queued_message_age_seconds`.
- `flowguard_processing_duration_seconds` histogram.
- `flowguard_retry_scheduled_total` by retry reason category.
- `flowguard_dead_lettered_total` by normalized error category.
- `flowguard_reconciliation_required_total`.
- `flowguard_http_requests_total` and latency histogram by route template and status family.
- Database pool utilization and health metrics from the approved instrumentation library.

Do not use message IDs, correlation IDs, arbitrary exception text, external references or user IDs as metric labels. They create unbounded cardinality and can expose sensitive information.

## 13.3 Tracing

Use W3C Trace Context where supported and adopt OpenTelemetry conventions for HTTP, database and messaging spans. Propagate trace context to supported outbound connectors, but keep business `correlationId` conceptually separate from a technical trace ID. Sampling and payload redaction must follow company telemetry policy.

## 13.4 Alert Candidates

Initial alert candidates (thresholds must be tuned using test/production evidence):

- Queue age exceeds approved business limit.
- Dead-letter count/rate crosses threshold.
- Reconciliation-required messages appear or increase unexpectedly.
- Processing failure rate spikes.
- Retry loop or target connector rate limiting is detected.
- Database readiness fails or connection pool is exhausted.
- Worker lease expiry/recovery increases above baseline.
- Disk space or table/index growth approaches operational limits.

Each production alert needs an owner, runbook link, severity, actionable diagnostic and escalation path. Alerts with no owner/action should not be enabled by default.

## 13.5 Runbook Minimum

- How to determine service health and the current deployment version.
- How to find a message by ID/correlation ID and view attempts safely.
- How to classify a target failure and decide whether retry is safe.
- How to retry an eligible message and verify the audit record.
- How to handle `RECONCILIATION_REQUIRED` without creating duplicate business effects.
- How to pause/resume processing during a target outage.
- How to rotate credentials and update destination configuration.
- How to back up and restore the database and verify restored data.
- How to roll back an application deployment and handle schema compatibility.

# 14. Configuration and Deployment

## 14.1 Configuration Principles

Configuration must be externalized by environment and validated on startup. Every setting needs a name, type, safe default (where applicable), allowed range, secret classification and operational owner. Fail fast for missing required production configuration. Do not silently select a demo target or development identity provider in a production profile.

Example configuration names (values intentionally omitted):

- `FLOWGUARD_DB_URL`, `FLOWGUARD_DB_USERNAME`, `FLOWGUARD_DB_PASSWORD` (secret from secret store, not committed)
- `FLOWGUARD_AUTH_ISSUER_URI`, `FLOWGUARD_AUTH_AUDIENCE`
- `FLOWGUARD_MAX_PAYLOAD_BYTES`
- `FLOWGUARD_WORKER_ENABLED`, `FLOWGUARD_WORKER_BATCH_SIZE`, `FLOWGUARD_WORKER_CONCURRENCY`
- `FLOWGUARD_LEASE_DURATION`, `FLOWGUARD_LEASE_RENEWAL_INTERVAL`
- `FLOWGUARD_RETRY_MAX_ATTEMPTS`, `FLOWGUARD_RETRY_INITIAL_DELAY`, `FLOWGUARD_RETRY_MAX_DELAY`, `FLOWGUARD_RETRY_JITTER_ENABLED`
- `FLOWGUARD_IDEMPOTENCY_RETENTION`
- `FLOWGUARD_MESSAGE_RETENTION`
- `FLOWGUARD_CONNECTOR_TIMEOUT`, `FLOWGUARD_CONNECTOR_ALLOWLIST` (future connector only; validate securely)
- `FLOWGUARD_TELEMETRY_ENDPOINT`, `FLOWGUARD_LOG_LEVEL`

## 14.2 Environments

| Environment | Purpose | Rules |
|---|---|---|
| Local | Developer feedback | Synthetic data; Docker Compose; development-only credentials; outbound delivery disabled or simulated |
| CI | Repeatable build/test | Ephemeral database; no production secrets; deterministic tests; security/static checks where available |
| Development/shared test | Integration verification | Isolated credentials and synthetic data; restricted access; migrations applied through pipeline |
| UAT | Business/operational acceptance | Approved test scenarios and masked/synthetic data unless separately authorized |
| Production | Real operations | Approved identity, secret store, TLS, private networking, backups, alerting, runbooks, change control and on-call ownership |

## 14.3 Containerization

- Use a minimal supported JRE/runtime base image and pin images by version or digest according to platform policy.
- Run as a non-root user; use read-only filesystem where compatible; drop unnecessary Linux capabilities.
- Do not bake secrets into Docker layers or image build arguments.
- Configure CPU/memory limits, graceful shutdown and stop timeout.
- Use health checks and ensure readiness only becomes true after critical initialization succeeds.
- Keep the database in a separate service/container for local development; production PostgreSQL should use the company's managed database standards where available.

## 14.4 Database Migration and Compatibility

- Every schema change is a reviewed, versioned migration.
- Never edit a migration that has already been applied in a shared environment; add a new migration.
- Follow expand/migrate/contract for schema changes that may overlap with rolling application deployment.
- Verify backup restore before high-risk schema changes.
- Application startup must not perform uncontrolled large data migrations.
- Production database rollback is not assumed possible for every migration; define forward-fix or restore plan in the release notes.

# 15. Technology Baseline and Dependency Policy

This section is a proposal, not a mandate to upgrade the existing repository blindly. First inspect the current build and align dependency changes with the company-approved platform stack.

| Concern | Proposed baseline | Notes |
|---|---|---|
| Language/runtime | Java LTS; Java 25 is a current LTS option as of this document date | Use Java 21 if required by the existing codebase or company runtime support; do not mix bytecode/runtime assumptions |
| Framework | Current supported Spring Boot release line compatible with the selected Java baseline | Spring lists 4.1.x as the latest stable line as of 17 October 2026; confirm migration compatibility before upgrading an existing Boot 3.x project |
| Build | Maven Wrapper and Maven dependency management | Pin wrapper; avoid relying on developer-global Maven versions |
| Persistence | PostgreSQL supported major release; 17/18 depending on organizational platform | PostgreSQL 18 is current and supported as of this document date; do not upgrade a company-managed database without approval |
| Migrations | Flyway or the existing approved migration tool | Exactly one schema migration authority |
| API contract | OpenAPI 3.1-compatible tooling unless 3.2 support is validated end to end | Commit the contract; test for drift |
| Validation | Jakarta Bean Validation at the adapter boundary plus domain factories | Domain invariants remain independent of framework annotations |
| Testing | JUnit 5, Mockito where appropriate, Spring test tooling, Testcontainers for PostgreSQL integration tests | Avoid mocks where a real database test is required to prove constraints/transactions |
| Telemetry | Micrometer/Actuator and/or OpenTelemetry according to platform standards | Do not expose management endpoints publicly |
| Delivery | Docker/Compose locally, organization-approved container runtime in deployment | Image scanning and non-root runtime |
| CI | GitHub Actions for portfolio/repository CI or the company-approved CI service | If company source code is involved, use company-controlled repositories/runners and policy |

## 15.1 Dependency Governance

- Use the framework dependency-management/BOM mechanism where possible.
- Commit Maven Wrapper files and lock down plugin versions.
- Avoid unmaintained packages, unnecessary Lombok magic in the domain model, and broad transitive dependency additions.
- Review licenses and transitive CVEs before release.
- Enable automated dependency update proposals; updates must pass tests and compatibility checks.
- Generate a Software Bill of Materials (SBOM) for production-bound releases if the company supply-chain policy requires it.
- Pin container base images according to the platform's patching and scanning policy.
- Record material upgrades and framework migrations in ADRs.

# 16. Quality Attributes and Proposed Non-Functional Requirements

All quantitative targets in this section are proposed planning values until agreed with the product owner, operations and target-system owners. Benchmark results must name environment, commit, dataset, concurrency and tool configuration.

| ID | Quality attribute | Proposed requirement / verification |
|---|---|---|
| NFR-01 | Durability | A message must not be acknowledged as accepted until message and idempotency record commit. Crash/restart test shows no loss of acknowledged messages within the tested failure model. |
| NFR-02 | Idempotency | Concurrent submissions using the same client/key create at most one message; same request returns same identity; different request conflicts. |
| NFR-03 | Availability | Production monthly SLO to be agreed. A 99.5% initial planning target may be evaluated but is not approved by this specification. |
| NFR-04 | Latency | Provisional load-test profile: 50 submit requests/second for 15 minutes with representative 10 KiB payloads and bounded 256 KiB edge payloads; measure p50/p95/p99; agree acceptance thresholds before UAT. |
| NFR-05 | Processing | Throughput objective must be derived from message arrival rate, target capacity and retry workload. No untested throughput is claimed. |
| NFR-06 | Scalability | Multiple workers must not claim the same active lease; verify under concurrent worker tests. |
| NFR-07 | Recovery | Proposed production RPO <= 15 minutes and RTO <= 4 hours for planning; obtain operations/database-owner sign-off and prove restore time. |
| NFR-08 | Security | Complete threat model, authorization tests, dependency scan, container scan and approved penetration/security review before live business payloads. |
| NFR-09 | Privacy | Data classes, retention, redaction and deletion approved by the data owner/security/privacy stakeholders. |
| NFR-10 | Maintainability | Domain unit tests run without Spring/DB; architecture tests prevent dependencies from adapters into domain. |
| NFR-11 | Portability | Local stack can run from documented steps; no hard dependency on a single cloud SDK in domain/application code. |
| NFR-12 | Observability | Logs and metrics expose processing behavior without high-cardinality labels or raw payloads. |
| NFR-13 | Accessibility/usability | If a UI is added, it follows company UI/accessibility standards; not an MVP dependency. |
| NFR-14 | Compatibility | API versioning and supported database/framework matrix are documented before each release. |

## 16.1 Performance Test Dataset

Use synthetic payloads representing small (1–5 KiB), common (approximately 10 KiB), and near-limit messages. Include success, transient error, permanent error and retry workloads. Test both a single worker and multiple workers. Record the Postgres version, connection pool settings, machine limits, commit SHA, dataset size, run duration and all important metrics. Do not publish a number as a service guarantee if the benchmark cannot be reproduced.

# 17. Testing Strategy

## 17.1 Domain Unit Tests

Domain unit tests are fast and run without Spring context or PostgreSQL. Minimum cases:

- Valid message creation; invalid message type, destination or identifier rejected.
- Every allowed lifecycle transition and every forbidden transition.
- Terminal states cannot be retried through ordinary processing.
- Attempt counts increment and max attempt boundary is respected.
- Retryable versus non-retryable error behavior.
- Ambiguous outcome moves to reconciliation under non-idempotent target capabilities.
- Idempotency policy compares stable semantic fingerprints.
- Payload/key/correlation length and format boundaries.

## 17.2 Application Use-Case Tests

Use fake output ports to verify orchestration and transaction intent:

- submit persists exactly one message and idempotency record;
- existing idempotent request returns the existing ID;
- conflicting fingerprint returns conflict;
- processing success/failure path selects the expected transition;
- stale lease result is rejected;
- manual retry requires authorization context, reason and eligible status;
- audit event is included for a privileged action;
- target adapter exceptions are normalized rather than leaked to API callers.

## 17.3 Persistence Integration Tests

Run against real PostgreSQL (preferably via Testcontainers or an approved CI service). Verify:

- migration from an empty database;
- all constraints and unique indexes;
- concurrent idempotent submissions;
- rollback of partial acceptance transaction;
- claim behavior using multiple workers and `SKIP LOCKED` where used;
- lease fencing and optimistic locking;
- ordering and pagination under inserts/updates;
- deletion/retention operations in bounded batches.

## 17.4 API/Contract Tests

- OpenAPI schema validation and example conformance.
- Authentication and authorization for each endpoint family.
- Content type, malformed JSON, oversized body, invalid field and unknown enum behavior.
- Problem Details error format and absence of sensitive diagnostics.
- Pagination bounds, sort allowlist and object-level authorization.
- Contract behavior for duplicate, conflict, timeout and unavailable database.

## 17.5 End-to-End / Resilience Tests

- Submit -> durable queue -> successful delivery -> query status.
- Deterministic transient failure -> scheduled retry -> eventual success.
- Permanent error -> dead-letter.
- Ambiguous timeout -> reconciliation-required; verify no blind retry.
- Application shutdown during work; verify lease recovery semantics.
- Database restart after accepted message; verify persistence after recovery.
- Concurrent worker test; no duplicate active claim, stale result update rejected.
- Target timeout and connection pool exhaustion simulation.

## 17.6 Architecture Tests

Add automated checks or package tests demonstrating that:

- domain packages do not depend on Spring/JPA/web/infra packages;
- application code depends on domain and ports only;
- adapters depend inward, never vice versa;
- dependency cycles between bounded-context modules are absent or explicitly approved;
- controllers are thin and contain no retry or lifecycle policy;
- no production code uses dev simulator configuration implicitly.

## 17.7 Coverage Policy

Do not set line coverage as the only measure of quality. A proposed minimum is 80% line coverage on domain/application code if it is achievable without contrived tests, combined with explicit branch coverage of lifecycle transitions and failure classification. Critical invariants and concurrency behavior must be tested even if overall coverage is high. The team can adjust the numeric target through a documented quality decision.

# 18. CI/CD, Release and Change Management

## 18.1 Pull Request Pipeline

Each pull request should run:

1. Format/style check and compilation.
2. Domain/application unit tests.
3. Persistence/API integration tests.
4. Architecture rule checks.
5. OpenAPI lint/validation.
6. Static analysis and dependency vulnerability review available in the approved tooling.
7. Container build and image scan when an image is produced.
8. Test report publication and clear failure summary.

A production deployment must require an approved branch protection policy and the company change/release process. For a personal public repository, GitHub Actions can demonstrate the pipeline; company code must follow company-controlled tooling and source-control rules.

## 18.2 Versioning

- Use semantic versioning for application releases where appropriate.
- Keep API versioning separate from internal application versioning.
- Record deployment artifact/image digest, Git commit and database migration level.
- Release notes list features, known issues, config changes, migration details, security fixes and rollback/forward-fix notes.
- Never disclose internal company names, endpoint URLs, payloads or secrets in public release notes.

## 18.3 Rollout Strategy

1. Local development and automated tests.
2. Shared development environment using synthetic data.
3. UAT with approved use cases and operator runbooks.
4. Limited pilot with a single approved client and destination, if architecture/security sign off.
5. Wider rollout only after metrics, error policy, support ownership, on-call and capacity are reviewed.

Each stage requires a go/no-go review. A demo success is not equivalent to production readiness.

# 19. Acceptance Criteria and Definition of Done

## 19.1 Functional Acceptance

- **AC-01:** A valid authenticated request creates a message and returns `202` only after the durable transaction commits.
- **AC-02:** Invalid JSON/schema/unknown message type is rejected without creating a message.
- **AC-03:** Repeating the same client/key/request creates no second message and returns the documented idempotent response.
- **AC-04:** Reusing the key with changed semantic content returns `409 IDEMPOTENCY_KEY_REUSED`.
- **AC-05:** A valid queued message can be claimed and processed by the simulator, and its state becomes `SUCCEEDED` with a completed attempt record.
- **AC-06:** A configured transient failure is retried according to the bounded backoff policy and can eventually succeed.
- **AC-07:** Permanent failure or exhausted retry budget results in `DEAD_LETTERED` with safe normalized error details.
- **AC-08:** Ambiguous remote outcome with no proven target idempotency results in `RECONCILIATION_REQUIRED`, not automatic blind retry.
- **AC-09:** Attempt history is queryable and completed attempts are not overwritten by subsequent attempts.
- **AC-10:** Manual retry requires the correct scope, a non-empty reason, eligible state and audit event.
- **AC-11:** A client cannot query or retry another client’s message by guessing its UUID.
- **AC-12:** Stale worker completion cannot overwrite a result recorded under a newer lease/version.
- **AC-13:** Search pagination, filters and sort values are bounded and deterministic.
- **AC-14:** Local startup and test execution follow the README on a clean machine with documented prerequisites.

## 19.2 Engineering Acceptance

- **AC-15:** Domain/application tests run without a Spring application context wherever practical.
- **AC-16:** PostgreSQL integration tests verify transaction and uniqueness behavior.
- **AC-17:** Architecture tests prevent outward dependencies from entering the domain.
- **AC-18:** OpenAPI file matches implemented endpoints and examples.
- **AC-19:** CI blocks merge on failed build/test/contract checks.
- **AC-20:** Logs contain trace/correlation identifiers but no raw payload or secret values.
- **AC-21:** Health, readiness and metrics endpoints are protected according to deployment policy.
- **AC-22:** Configuration validates required production settings and refuses demo connector/auth in production profile.
- **AC-23:** Runbook covers failure review, safe retry, reconciliation, backup/restore and deployment rollback/forward-fix.

## 19.3 Production Readiness Gates

The MVP is not ready for production merely because AC-01 through AC-23 pass. Production additionally requires:

- approved data classification, lawful purpose, payload retention and deletion policy;
- threat model and security review including object-level authorization and target connector SSRF controls;
- approved identity provider, secrets management and network/egress architecture;
- selected target connector contract and idempotency/reconciliation semantics;
- signed SLOs, capacity targets, RPO/RTO and load-test report;
- monitoring dashboards, actionable alerts, operational owner and on-call process;
- tested backup/restore and documented disaster recovery;
- migration/rollback or forward-fix strategy;
- business UAT and change approval;
- documented support model, incident classification and escalation path.

# 20. Risks, Mitigations and Open Decisions

| ID | Risk / open decision | Consequence | Proposed mitigation / owner |
|---|---|---|---|
| R-01 | Existing code and current dependency versions not audited | Spec may differ from implementation | Conduct repository inventory; author/technical lead |
| R-02 | Target API/receiver not selected | Error semantics and delivery guarantees remain hypothetical | Select one sanctioned target and document contract; integration owner |
| R-03 | External target lacks idempotency/status query | Duplicate business side effect after timeout | Reconciliation state and manual business verification; target owner |
| R-04 | Payload may contain personal/financial data | Privacy, confidentiality and retention risk | Data classification and payload-read scope; data owner/security |
| R-05 | Retry policy not aligned to target SLA/rate limits | Retry storm or late processing | Approve max attempts/backoff/Retry-After and circuit policy; integration owner |
| R-06 | DB queue throughput may not meet future volume | Queue age and latency increase | Benchmark, tune indexes/workers; broker evaluation only if evidence supports it |
| R-07 | Retention and audit policy undefined | Database growth or premature deletion | Define retention by data class and object type; records owner |
| R-08 | Java/Spring/DB version mismatch with host platform | Upgrade or deployment incompatibility | Adopt company platform matrix; no blind dependency major upgrades |
| R-09 | Operational retry permissions too broad | Unauthorized duplicate processing | Least-privilege scope, audit, review and reconciliation controls; security owner |
| R-10 | Demo used as implicit production approval | Operational/security exposure | Label demo, enforce production profile fail-fast and formal go/no-go |

## 20.1 Decisions Required Before Live Connector Work

1. Which source client(s), message type(s) and destination are in scope?
2. Which target API/protocol and business operation will be invoked?
3. Does the target support idempotency keys, correlation search or reliable status inquiry?
4. Which identity provider and service-to-service auth flow are mandatory?
5. What data classification applies, and may full payloads be stored at rest?
6. What are the required throughput, latency, message size, ordering and delivery windows?
7. What are the business retention, audit, RPO, RTO and availability requirements?
8. Which platform handles API gateway, secrets, logging, metrics, deployment and backups?
9. Who owns the service and operates alerts after the developer hands it over?
10. Is FlowGuard an internal experiment, a reusable engineering component or a candidate for a formally supported service?

# 21. Repository and Documentation Deliverables

The repository should include the following artifacts at minimum:

- `README.md`: purpose, scope, quick start, prerequisites, supported profiles, tests, API docs, security notice and limitations.
- `docs/SPECIFICATION.md`: this specification.
- `docs/IMPLEMENTATION_PLAN.md`: phases, tasks and milestones.
- `docs/architecture/`: context/container/component diagrams and package-boundary decisions.
- `docs/adr/`: architecture decisions and superseded decisions.
- `docs/runbooks/`: development/operations procedures, including failure triage and safe recovery.
- `docs/security/`: threat model, data classification assumptions and production-readiness checklist (no secret values).
- `openapi/flowguard-v1.yaml` or generated-and-validated equivalent.
- `src/test/`: unit, persistence, web/contract, architecture and end-to-end tests.
- `db/migration/`: versioned schema migrations.
- `.github/workflows/`: build/test/quality pipeline for a personal repository, or equivalent company-controlled CI config.
- `docker-compose.yml`: local-only development services using non-production settings.
- `.env.example`: placeholders only.

# 22. Glossary

| Term | Definition |
|---|---|
| Adapter | Infrastructure code that translates an external technology/protocol into an application port or implements an outbound port |
| Aggregate | DDD consistency boundary that protects domain invariants |
| Bounded context | Explicit boundary within which a domain model and vocabulary have a consistent meaning |
| Correlation ID | Business/operational identifier used to follow related work across systems |
| Dead letter | A message state where automatic processing stops and investigation is required |
| Domain event | A meaningful fact that occurred in the domain; not the same as event sourcing |
| Hexagonal Architecture | Architecture that isolates application/domain behavior behind ports and adapters |
| Idempotency | Repeated execution with the same logical key does not create an unintended duplicate effect |
| Lease/fencing token | Time-bounded claim and unique token used to reject stale workers |
| Outbox | A durable record used to atomically store a business change and a future outgoing event; deferred unless needed |
| Port | An application-defined interface representing a use case or an external capability |
| Reconciliation | Establishing the actual business outcome when technical delivery status is ambiguous |
| Retry/backoff/jitter | Controlled repeat attempt with increasing delay and random variation |
| SLO / SLA | Service-level objective / agreement; targets are not contractual until approved |
| Trace ID | Identifier for a technical distributed trace; distinct from business correlation ID |

# 23. External References

These public references support the proposed technology/security conventions. Version compatibility must still be checked against the repository and the organization's approved platform matrix at implementation time.

1. Oracle, **Java SE Support Roadmap** (Java 25 LTS and support lifecycle): https://www.oracle.com/java/technologies/java-se-support-roadmap.html
2. Spring, **Projects / release calendar** (Spring Boot release line): https://spring.io/projects/
3. Spring Boot, **System Requirements**: https://docs.spring.io/spring-boot/4.0/system-requirements.html
4. PostgreSQL, **Versioning Policy**: https://www.postgresql.org/support/versioning/
5. PostgreSQL, **SELECT and row-locking clauses** (`FOR UPDATE`, `SKIP LOCKED`): https://www.postgresql.org/docs/18/sql-select.html
6. OpenAPI Initiative, **OpenAPI Specification versions**: https://spec.openapis.org/oas/
7. IETF, **RFC 9457 — Problem Details for HTTP APIs**: https://www.rfc-editor.org/rfc/rfc9457.html
8. OWASP, **API Security Top 10 — 2023**: https://owasp.org/www-project-api-security/
9. OpenTelemetry, **Semantic Conventions**: https://opentelemetry.io/docs/specs/semconv/
10. W3C, **Trace Context**: https://www.w3.org/TR/trace-context/

---

# Appendix A. Reference Architecture in One View

**Inbound path:** REST Controller → Submit Message Input Port → Submit Message Use Case → Domain Factory / Idempotency Policy → Message Repository + Idempotency Repository (one transaction) → HTTP 202 response.

**Processing path:** Worker Trigger → Claim Due Messages Input Port → Message Repository claims under lease → Processing Use Case → Delivery Port → Simulation Adapter → Result Classification → Message Repository + Attempt Repository updates under lease fencing.

**Operational path:** REST query adapter → Search/Get/Retry input ports → query repositories or domain command → audit repository for privileged action.

**Infrastructure path:** PostgreSQL adapter, clock/ID providers where abstraction is justified, structured logging/metrics/tracing adapters, identity-provider integration, and deployment configuration. None of these should be referenced directly by domain entities.

# Appendix B. Minimum ADR Set

- ADR-001: Modular monolith and Hexagonal Architecture/DDD boundaries.
- ADR-002: Database-backed queue versus external broker for MVP.
- ADR-003: Idempotency scope, fingerprint canonicalization and retention.
- ADR-004: Status machine, ambiguous outcomes and target idempotency contract.
- ADR-005: Authentication and authorization model.
- ADR-006: Payload persistence, classification, masking and retention.
- ADR-007: Logging, metrics and trace propagation.
- ADR-008: Runtime/framework/database version matrix.
- ADR-009: Production connector allowlist and SSRF controls.
- ADR-010: Backup/restore, SLO, RPO/RTO and operational ownership.

# Appendix C. Proposal Guardrails

This document describes a proposed target state and reviewable requirements. It does not represent approval by the employer, a confirmed production deployment design, a security certification, a capacity guarantee or proof that the existing repository already implements each requirement. Any adaptation for company use should be reviewed under company architecture, data security, code ownership, source-control and change-management rules. No production credential, customer payload, private endpoint or proprietary design should be copied into a personal/public repository.
