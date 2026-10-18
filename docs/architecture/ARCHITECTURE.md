# FlowGuard Enterprise Architecture Specification

## 1. Hexagonal Architecture (Ports & Adapters)

FlowGuard enforces an inward-facing dependency model adhering to Hexagonal Architecture and Domain-Driven Design (DDD).

```mermaid
graph TD
    subgraph Inbound Adapters ["Inbound Adapters (Driving)"]
        REST["REST API (MessageController)"]
        OPS["Operations API (OperationsController)"]
        SCHED["Worker Scheduler (WorkerScheduler)"]
        RECOV["Lease Recovery Scheduler (LeaseRecoveryScheduler)"]
    end

    subgraph Application Core ["Application Core / Ports"]
        SUB_UC["SubmitMessageUseCase"]
        GET_UC["GetMessageUseCase"]
        SEARCH_UC["SearchMessagesUseCase"]
        ATT_UC["GetAttemptsUseCase"]
        RETRY_UC["ManualRetryUseCase"]
        REC_UC["RecoverExpiredLeasesUseCase"]
        PROC_UC["ProcessMessagesUseCase"]
        OPS_UC["GetOperationsSummaryUseCase"]
        
        MSG_PORT["MessageRepositoryPort"]
        IDEMP_PORT["IdempotencyRepositoryPort"]
        ATT_PORT["AttemptRepositoryPort"]
        DELIV_PORT["DeliveryPort"]
        AUDIT_PORT["AuditRepositoryPort"]
    end

    subgraph Pure Domain ["Pure Domain Model (Zero Framework Imports)"]
        AGG["IntegrationMessage Aggregate Root"]
        ATT["ProcessingAttempt Entity"]
        AUD["AuditEvent Record"]
        POLICY["Lifecycle, Retry & Idempotency Policies"]
        VO["Value Objects (MessageId, ClientId, DestinationKey, etc.)"]
    end

    subgraph Outbound Adapters ["Outbound Adapters (Driven)"]
        PG_MSG["PostgresMessageRepositoryAdapter"]
        PG_IDEMP["PostgresIdempotencyRepositoryAdapter"]
        PG_ATT["PostgresAttemptRepositoryAdapter"]
        PG_AUDIT["PostgresAuditRepositoryAdapter"]
        SIM_DELIV["SimulationDeliveryAdapter (@Profile('!prod'))"]
    end

    REST --> SUB_UC
    REST --> GET_UC
    REST --> SEARCH_UC
    REST --> ATT_UC
    REST --> RETRY_UC
    OPS --> OPS_UC
    SCHED --> PROC_UC
    RECOV --> REC_UC

    SUB_UC -.-> MSG_PORT
    SUB_UC -.-> IDEMP_PORT
    PROC_UC -.-> MSG_PORT
    PROC_UC -.-> DELIV_PORT
    PROC_UC -.-> ATT_PORT
    RETRY_UC -.-> MSG_PORT
    RETRY_UC -.-> AUDIT_PORT
    REC_UC -.-> MSG_PORT
    REC_UC -.-> ATT_PORT

    MSG_PORT --> AGG
    IDEMP_PORT --> AGG
    ATT_PORT --> ATT
    AUDIT_PORT --> AUD

    PG_MSG -.->|implements| MSG_PORT
    PG_IDEMP -.->|implements| IDEMP_PORT
    PG_ATT -.->|implements| ATT_PORT
    PG_AUDIT -.->|implements| AUDIT_PORT
    SIM_DELIV -.->|implements| DELIV_PORT
```

---

## 2. Message Lifecycle State Machine

```mermaid
stateDiagram-v2
    [*] --> QUEUED : Ingestion Accepted
    QUEUED --> PROCESSING : Worker Claim (FOR UPDATE SKIP LOCKED + Lease)
    PROCESSING --> SUCCEEDED : Success (Under Lease Fence)
    PROCESSING --> RETRY_SCHEDULED : Transient Error & Attempts < Max
    PROCESSING --> DEAD_LETTERED : Permanent Error OR Attempts >= Max
    PROCESSING --> RECONCILIATION_REQUIRED : Ambiguous Timeout / Expired Lease
    RETRY_SCHEDULED --> PROCESSING : Due Attempt Claimed
    DEAD_LETTERED --> QUEUED : Audited Manual Retry Command
    RECONCILIATION_REQUIRED --> QUEUED : Audited Manual Retry Command
    QUEUED --> CANCELLED : Explicit Operator Cancellation
    RETRY_SCHEDULED --> CANCELLED : Explicit Operator Cancellation
    SUCCEEDED --> [*]
    CANCELLED --> [*]
```

---

## 3. High-Concurrency Idempotency Race Resolution

```mermaid
sequenceDiagram
    autonumber
    actor CallerA as Client Thread A
    actor CallerB as Client Thread B
    participant Ingestion as SubmitMessageService
    participant DB as PostgreSQL (idempotency_record)

    CallerA->>Ingestion: submit(Key=K, Payload=P)
    CallerB->>Ingestion: submit(Key=K, Payload=P)
    Ingestion->>DB: INSERT INTO idempotency_record ... ON CONFLICT DO NOTHING (Thread A)
    DB-->>Ingestion: 1 row affected (Thread A Wins)
    Ingestion->>DB: INSERT INTO idempotency_record ... ON CONFLICT DO NOTHING (Thread B)
    DB-->>Ingestion: 0 rows affected (Thread B Detects Conflict)
    Note over Ingestion,DB: Thread B removes uncommitted orphan message and reloads winning record
    Ingestion->>DB: SELECT * FROM idempotency_record WHERE key_digest = K
    DB-->>Ingestion: Winning Record (from Thread A)
    Ingestion->>Ingestion: Verify Fingerprints Match
    Ingestion-->>CallerA: 202 Accepted (Message ID: M1, replay: false)
    Ingestion-->>CallerB: 200 OK (Message ID: M1, replay: true)
```

---

## 4. Worker Claim & Lease Fencing

```mermaid
sequenceDiagram
    autonumber
    participant Worker as WorkerScheduler / ProcessMessagesService
    participant DB as PostgreSQL
    participant Target as External Target / Simulator

    Worker->>DB: SELECT id ... FOR UPDATE SKIP LOCKED LIMIT 10
    DB-->>Worker: Locked Rows
    Worker->>DB: UPDATE integration_message SET status='PROCESSING', lease_token=T1, lease_until=now()+60s
    DB-->>Worker: Committed (Short DB Transaction 1 Ends)
    
    Note over Worker,Target: Non-blocking HTTP Call executed OUTSIDE DB Transaction
    Worker->>Target: POST /deliver(payload)
    Target-->>Worker: 200 OK
    
    Worker->>DB: UPDATE integration_message SET status='SUCCEEDED' WHERE id=M AND lease_token=T1
    alt Lease Token Matches T1
        DB-->>Worker: 1 row updated (Work Accepted)
    else Lease Token Superseded (Worker Stale)
        DB-->>Worker: 0 rows updated (Stale Update Safely Dropped)
    end
```
