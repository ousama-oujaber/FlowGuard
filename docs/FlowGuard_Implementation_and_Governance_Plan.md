---
title: "FlowGuard — Implementation, Verification and Governance Plan"
author: "Ousama Oujaber"
date: "17 October 2026"
version: "1.0"
status: "Companion delivery plan for technical review"
---

# 1. Purpose

This plan translates `FlowGuard_Enterprise_Specification.md` into an implementation sequence and a reviewable engineering workflow. It assumes an initial codebase already exists and reportedly uses Hexagonal Architecture and DDD. The repository itself has not been audited in preparing this document, so the first phase inventories the current implementation and records what is already complete, incomplete, or different from the target specification. **Do not rebuild working code or change framework majors before that review.**

The delivery target is an MVP demonstration with credible engineering evidence, not an unapproved production launch. Estimate: 6–8 weeks at 1–2 focused hours per day for one developer, depending on how much of the current code is already implemented, the quality of existing tests, and the time required to learn unfamiliar tools. Estimates are planning aids, not a delivery promise.

# 2. Delivery Principles

1. Preserve existing working architecture unless an ADR explains a measured reason to change it.
2. Complete one end-to-end vertical slice early: submit → persist → process with simulator → inspect result.
3. Prefer small pull requests and tested increments over a large initial refactor.
4. Write the failing/edge-case test with the feature, not weeks later.
5. Treat idempotency, concurrency and security as correctness requirements, not optional polish.
6. Separate MVP functionality from future connectors, UI and broker decisions.
7. Keep all examples and demo data synthetic; never introduce employer secrets or production payloads.
8. Keep a visible list of assumptions, decisions, risks and open questions.

# 3. Phase 0 — Repository Inventory and Baseline (2–4 sessions)

## Tasks

- [ ] Identify repository root, current branch and working tree status.
- [ ] Read `pom.xml` or the actual build file; record Java toolchain, framework, dependencies, plugins and test commands.
- [ ] Read package structure and map domain, application, inbound adapters, outbound adapters and bootstrap/configuration.
- [ ] Inventory aggregates, entities, value objects, domain policies and use cases.
- [ ] Inventory controllers, persistence adapters, repository interfaces, connectors and schedulers/workers.
- [ ] Inspect migrations, constraints, indexes and data-retention assumptions.
- [ ] Identify existing tests by type and run the complete suite from a clean state.
- [ ] Record API endpoints and the current contract/documentation.
- [ ] Record secrets/config patterns and check that no actual credentials or business payloads are tracked.
- [ ] Inventory CI workflows, Docker files, health endpoints and observability configuration.
- [ ] Create a conformance matrix against the main specification.
- [ ] Create ADRs only for actual decisions; do not write a false history for decisions already made.

## Deliverables

- `docs/implementation-inventory.md`
- A green/red/partial matrix for AC-01 through AC-23.
- A list of blockers and technical debt, ordered by delivery risk.
- A baseline build/test command and repository commit hash.

## Exit Criteria

The developer can explain the current module boundaries, run the existing tests, and identify the single smallest end-to-end feature to finish next. No speculative rewrite has begun.

# 4. Phase 1 — Domain and Application Contract (Week 1)

## Tasks

- [ ] Confirm the domain language with a short glossary: message, attempt, destination, correlation, idempotency, retry, dead letter and reconciliation.
- [ ] Document `IntegrationMessage` lifecycle and invariants.
- [ ] Finalize status transition rules and failure taxonomy.
- [ ] Define `MessageId`, `ClientId`, `MessageType`, `DestinationKey`, `CorrelationId`, `IdempotencyKey` and `PayloadFingerprint` value objects as required.
- [ ] Define inbound use-case ports for submit, get, search, list attempts and request retry.
- [ ] Define outbound ports for message persistence, idempotency storage, attempts, audit and delivery.
- [ ] Keep transport DTOs separate from domain objects.
- [ ] Add unit tests for valid/invalid state transitions and retry eligibility.
- [ ] Add architecture tests or build checks to prevent dependency direction violations.

## Exit Criteria

- The domain model is understandable without reading Spring controllers or JPA entities.
- Lifecycle transitions are specified and tested.
- Interfaces are expressed in application-owned types; adapters implement those interfaces.

# 5. Phase 2 — First Vertical Slice: Submit and Persist (Week 1–2)

## Tasks

- [ ] Establish request/response DTOs for message submission.
- [ ] Implement controller as an inbound adapter; it delegates to an input port.
- [ ] Enforce authentication principal mapping and allowed message/destination values.
- [ ] Validate JSON content type, payload shape, identifier lengths and payload size.
- [ ] Implement message repository port and PostgreSQL adapter.
- [ ] Add versioned migrations for the required tables/constraints only.
- [ ] Persist message plus idempotency record atomically.
- [ ] Implement `202 Accepted` with message ID and `Location` link.
- [ ] Implement standardized validation and error responses.
- [ ] Add unit, persistence and API tests; verify DB rollback on partial failure.
- [ ] Update OpenAPI and README quick start.

## Exit Criteria

A developer can run the service and database, submit a synthetic JSON payload, query the persisted message, and verify a failure does not create a phantom accepted message.

# 6. Phase 3 — Worker, Attempt History and Simulator (Week 2–3)

## Tasks

- [ ] Define `DeliveryPort` in application/core-facing code.
- [ ] Implement `SimulationDeliveryAdapter` only for the MVP.
- [ ] Support deterministic simulation scenarios: success; fail transiently for N attempts then succeed; permanent failure; unknown/timeout outcome.
- [ ] Add worker trigger/runner as an inbound adapter to the application use case.
- [ ] Implement due-message claim logic with short transaction and lease token.
- [ ] Implement attempt creation and monotonic attempt numbering.
- [ ] Invoke the adapter outside the database transaction.
- [ ] Persist result only if lease token/version is still current.
- [ ] Add stale-worker and worker-crash tests.
- [ ] Make it impossible for a production profile to use the simulator by default.

## Exit Criteria

A message is processed by the simulator, its status and attempt history are persisted, and a simulated permanent/transient/unknown outcome produces the expected state.

# 7. Phase 4 — Idempotency, Retry and Recovery (Week 3–4)

## Tasks

- [ ] Choose/document JSON semantic canonicalization for the request fingerprint.
- [ ] Hash/store idempotency key and request fingerprint according to the specification.
- [ ] Enforce unique `(client scope, key digest)` using a database constraint.
- [ ] Handle concurrent insertion conflict by loading and comparing the winning record.
- [ ] Verify duplicate same request returns the original message identity.
- [ ] Verify same key/different request returns `409` and creates no second message.
- [ ] Implement retry policy with max attempts, exponential backoff, cap and jitter.
- [ ] Treat non-retryable and ambiguous outcomes separately.
- [ ] Add expired lease detection/recovery.
- [ ] Implement manual retry command with scope validation, reason and audit record.
- [ ] Test retry command idempotency to avoid duplicate operator actions.
- [ ] Document target adapter idempotency capabilities; keep unknown-outcome messages out of automatic retries when unsafe.

## Exit Criteria

Concurrency tests demonstrate a single accepted message per client/key and stale workers cannot overwrite newer results. Retry and recovery paths are deterministic under tests.

# 8. Phase 5 — Operations API and Security Hardening (Week 4–5)

## Tasks

- [ ] Add authorized get-message, search and attempt-history use cases/endpoints.
- [ ] Enforce message/object-level authorization in each query and action.
- [ ] Add pagination, filter allowlist, date-range bounds and stable sorting.
- [ ] Separate payload-read permission from metadata-read permission.
- [ ] Add audit record persistence for retry/reconciliation actions.
- [ ] Add structured safe logging with message/correlation/trace fields.
- [ ] Add health/readiness and internal metrics with exposure controlled by profile/network policy.
- [ ] Verify no raw payload, credential, authorization header or remote response leaks to logs/errors.
- [ ] Add request limits, endpoint rate limits where available, DB timeouts and worker concurrency limits.
- [ ] Add negative tests for unauthorized cross-client lookup/retry and invalid scopes.
- [ ] Write threat-model notes for current API and future HTTP connector.

## Exit Criteria

All endpoints are access-controlled, operational history is useful but safe, and the security checklist has test evidence for the highest-risk controls.

# 9. Phase 6 — Quality Gates, Documentation and Demo Readiness (Week 5–6)

## Tasks

- [ ] Run unit, persistence, API, architecture and end-to-end test suites.
- [ ] Use real PostgreSQL in integration tests; verify migrations from an empty database.
- [ ] Validate OpenAPI and sample payloads against the implementation.
- [ ] Add Docker Compose for local development with no production-like secrets.
- [ ] Add Maven Wrapper if absent and document supported JDK/build versions.
- [ ] Configure CI to compile, run tests and fail on contract/architecture failures.
- [ ] Add static analysis/dependency vulnerability checks supported by the chosen environment.
- [ ] Add architecture and lifecycle diagrams to `docs/architecture/`.
- [ ] Complete README, local quick start, API examples, known limitations and security notes.
- [ ] Prepare runbook for failure triage, safe retry, reconciliation and restore.
- [ ] Produce a reproducible synthetic demo script and test report.
- [ ] Mark all incomplete production-readiness items visibly.

## Exit Criteria

A reviewer can clone the repo, run it, reproduce the main scenarios and trace requirements to tests. No production claims are made unless the appropriate gates have actually been approved.

# 10. Optional Phase 7 — Company-Approved Connector Pilot

This phase begins only after a real target system, data classification, network paths, identity method and retry semantics have been approved.

- [ ] Define the target API contract, success/failure codes, timeouts, rate limits and status-query capability.
- [ ] Confirm target idempotency token support or reconciliation strategy.
- [ ] Define destination registration and network egress allowlists.
- [ ] Integrate through a new adapter behind the existing `DeliveryPort`; do not add target SDK classes to domain/application code.
- [ ] Retrieve credentials from the company secret store/workload identity, not the repository or database plaintext.
- [ ] Add target-specific contract, timeout, malformed response, retry-after and SSRF/redirect tests.
- [ ] Conduct security, architecture, operational and data-owner review.
- [ ] Pilot in an isolated environment with synthetic/masked data before live messages.

A SAP connector is not assumed to be part of the MVP. The protocol may be REST/OData, SOAP or another company-approved interface; selection follows the target system and architecture decision, not the desire to demonstrate a particular technology.

# 11. Suggested GitHub Issues

Create one issue per deliverable, and keep each issue small enough for a focused pull request.

| Issue | Title | Depends on | Completion evidence |
|---|---|---|---|
| FG-001 | Inventory current repository and dependencies | None | Inventory document and baseline test report |
| FG-002 | Define message lifecycle and domain invariants | FG-001 | Domain tests for allowed/forbidden transitions |
| FG-003 | Define submit/get/retry application ports | FG-002 | Ports use application-owned types |
| FG-004 | Add message and idempotency schema migration | FG-001–003 | Migration passes on empty DB and rollback/forward plan documented |
| FG-005 | Implement message acceptance use case | FG-003–004 | Unit and persistence tests pass |
| FG-006 | Implement submit REST adapter and OpenAPI | FG-005 | Contract tests and 202 example pass |
| FG-007 | Add simulator adapter and attempt records | FG-003–006 | Deterministic success/failure scenarios pass |
| FG-008 | Implement worker claim lease and stale-result fencing | FG-007 | Multi-worker concurrency tests pass |
| FG-009 | Implement idempotency race handling | FG-004–006 | Concurrent duplicate submission test passes |
| FG-010 | Implement retry classifier/backoff/dead letter | FG-007–008 | Retry and permanent-failure scenarios pass |
| FG-011 | Implement safe ambiguous-outcome reconciliation | FG-010 | Unknown outcome does not auto-retry when unsafe |
| FG-012 | Add search and attempt-history APIs | FG-006–010 | Pagination and authorization tests pass |
| FG-013 | Add audited manual retry command | FG-010–012 | Scope, reason, status and audit tests pass |
| FG-014 | Add security hardening and resource limits | FG-006–013 | Threat checklist with test evidence |
| FG-015 | Add logs, metrics and health checks | FG-007–014 | Telemetry verified without sensitive fields |
| FG-016 | Add Docker Compose and local quick start | FG-001–015 | Clean-machine smoke test passed |
| FG-017 | Add CI and dependency/architecture checks | FG-001–016 | Pull request workflow is green and blocks known failures |
| FG-018 | Add runbook, demo script and release note | FG-001–017 | Reviewer can reproduce the demo and recovery steps |
| FG-019 | Production-readiness review checklist | FG-001–018 | Open issues assigned; no implied production approval |

# 12. Pull Request Checklist

Use this checklist in each feature PR:

- [ ] Requirement or issue linked.
- [ ] Domain/application rule implemented in the correct layer.
- [ ] Input validation and safe failure response considered.
- [ ] No domain-to-framework/database dependency introduced.
- [ ] Authorization/data scope checked for read or write operations.
- [ ] Database constraints and transaction boundary reviewed where applicable.
- [ ] Normal, boundary, failure and concurrency tests added as relevant.
- [ ] OpenAPI/README updated if behavior changed.
- [ ] Logs contain useful identifiers but no payload/secrets.
- [ ] Migration is forward-compatible and has a documented operational path.
- [ ] CI passes; no test is disabled merely to make the pipeline green.
- [ ] Public documentation contains no employer-sensitive information.

# 13. Branching and Commit Guidance

A lightweight GitHub workflow is sufficient:

- `main`: always buildable and releasable to the demo environment.
- Short-lived feature branches: `feat/fg-005-message-acceptance`, `fix/fg-009-idempotency-race`, `docs/fg-018-runbook`.
- Small, reviewable pull requests; squash or otherwise follow the repository's documented commit policy.
- Example commit messages: `feat: add durable message submission`, `test: cover idempotency race`, `fix: reject stale worker completion`, `docs: define reconciliation procedure`.
- Never commit secrets, real payloads, `.env` files, customer logs or company-generated configuration.

# 14. Architecture Decision Records

Use a short consistent template:

```text
# ADR-NNN: Decision title
Status: Proposed | Accepted | Superseded
Date:
Context:
Decision:
Alternatives considered:
Consequences:
Security/data implications:
Operational implications:
Validation / revisit trigger:
```

Minimum decisions to record: modular monolith/hexagonal structure; database-backed queue vs broker; idempotency semantics/retention; unknown target outcome; auth scopes; payload retention/redaction; telemetry; runtime version matrix; connector egress/allowlist; backup/restore and service ownership.

# 15. Definition of Ready and Definition of Done

## Definition of Ready

A feature is ready to implement when its expected behavior and edge cases are described, acceptance criteria are testable, domain owner is known, API/data changes are identified, security implications are considered, dependencies are named and no unresolved question blocks a safe implementation.

## Definition of Done

A feature is complete when the implementation is in the correct layer, its acceptance criteria pass, tests cover normal and relevant failure/concurrency paths, API/schema/documentation changes are included, architecture constraints are preserved, CI is green, logs/errors are safe, migrations/configuration are documented and known limitations are recorded.

# 16. Demo Script for Engineering Review

Run the demonstration only with local synthetic data:

1. Start the database and service using the documented local setup.
2. Authenticate using a development-only identity/API key.
3. Submit a valid message; show `202`, message ID and `QUEUED` state.
4. Submit the same request with the same idempotency key; show that it returns the same message rather than creating another record.
5. Reuse the key with altered payload; show `409 IDEMPOTENCY_KEY_REUSED`.
6. Trigger simulator success; inspect `SUCCEEDED` and attempt history.
7. Trigger a transient failure followed by success; show next-attempt schedule and final outcome.
8. Trigger a permanent failure; show `DEAD_LETTERED` and safe error summary.
9. Trigger an ambiguous timeout; show `RECONCILIATION_REQUIRED` and absence of automatic retry.
10. Attempt to read/retry a message under a different client scope; show denial or scoped not-found behavior.
11. Request a manual retry with the correct scope and reason; show the audit event.
12. Run automated tests and show CI status.
13. Explain which controls are implemented, which are proposed, and what still blocks production.

# 17. Review Checklist Before Presenting to a Company

- [ ] Replace any outdated facts with the actual current repository structure and versions.
- [ ] Separate implemented capabilities from target requirements.
- [ ] Remove all company/customer/tenant identifiers and real payload examples.
- [ ] Confirm that no employer-owned source code or confidential design is included in the public repository.
- [ ] Agree whether the proposal should live in a personal repo or a company-controlled repository.
- [ ] Present it as a proposal/technical proof of concept, not as an approved company system.
- [ ] Ask for feedback on one concrete pilot use case rather than proposing broad platform adoption immediately.
- [ ] Identify the technical owner, target-system owner, security reviewer and operations owner for any next phase.

# 18. Definition of a Successful Hand-Off

A reviewer should receive:

1. A concise problem statement and target use case.
2. A specification with scope, domain rules, APIs, data model, security and SLO decisions.
3. A working demonstration with synthetic data and repeatable setup.
4. Automated evidence for idempotency, retries, state transitions and authorization.
5. A list of open decisions/risks with named owners or explicit “owner to be assigned”.
6. A clear statement of what is and is not production-ready.

This is the difference between a promising personal project and an engineering proposal that an enterprise team can responsibly evaluate.
