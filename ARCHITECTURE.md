# Architecture and schema

```mermaid
flowchart LR
  HR[HR integration] --> Security[JWT / RBAC / tenant guards]
  Admin[Transport admin] --> Security
  Operator[Operator] --> Security
  Employee[Employee self-service] --> Security
  Security --> Events[Event batch validation and processor]
  Security --> Transport[Transport service]
  Security --> Snapshots[CSV / staging / diff / approval]
  Security --> Directory[Profile and office reads]
  Security --> Reserve[Booking reservation]
  Events --> PG[(PostgreSQL)]
  Transport --> PG
  Snapshots --> PG
  Snapshots --> Files[(Immutable snapshot volume)]
  Directory --> PG
  Directory --> Redis[(Redis)]
  Reserve --> PG
  PG --> Outbox[Leased outbox relay]
  Outbox --> Rabbit[RabbitMQ durable exchange / queue]
  Rabbit --> Consumer[Inbox / booking cancellation]
  Consumer --> PG
  Rabbit --> DLQ[Dead queue]
  Outbox --> Dead[Durable DEAD outbox records]
```

```mermaid
sequenceDiagram
  participant HR
  participant API
  participant DB as PostgreSQL
  participant Relay
  participant Rabbit
  participant Booking
  HR->>API: HR event with eventId / sourceVersion
  API->>DB: Begin READ COMMITTED; lock tenant
  API->>DB: Check receipt and canonical version
  alt duplicate or stale
    API->>DB: No employee/outbox mutation
  else newer valid event
    API->>DB: Explicit HR mutation + receipt + version + outbox
  end
  API->>DB: Commit
  API-->>HR: Per-record safe result
  Note over DB,Relay: Process may die here; pending outbox survives
  Relay->>DB: SKIP LOCKED claim with lease/token; commit
  Relay->>Rabbit: Persistent mandatory publish
  Rabbit-->>Relay: Confirm
  Note over Relay,Rabbit: Death here may produce duplicate delivery
  Relay->>DB: Fenced mark PUBLISHED
  Rabbit->>Booking: EMPLOYEE_DEACTIVATED
  Booking->>DB: Begin; inbox dedup + cancellations + seat release
  Booking->>DB: Commit
  Booking-->>Rabbit: Ack
```

```mermaid
stateDiagram-v2
  [*] --> STAGING
  STAGING --> FAILED: upload or staging infrastructure failure
  FAILED --> STAGING: reclaim and restage durable file
  STAGING --> INVALID: empty / malformed / invalid rows / conflict
  STAGING --> READY: complete safe diff
  STAGING --> BLOCKED: missing fraction reaches threshold
  READY --> APPROVED: exact checksum and revision
  BLOCKED --> APPROVED: explicit operator override
  APPROVED --> READY: review invalidated and safe
  APPROVED --> BLOCKED: review invalidated and unsafe
  APPROVED --> INVALID: references or equal-version state conflict
  APPROVED --> COMPLETED: atomic apply and outbox
  COMPLETED --> COMPLETED: idempotent repeated apply
```

```mermaid
erDiagram
  TENANTS ||--o{ OFFICES : owns
  TENANTS ||--o{ EMPLOYEES : owns
  OFFICES ||--o{ EMPLOYEES : assigned
  EMPLOYEES o|--o{ EMPLOYEES : manager
  TENANTS ||--o{ PROCESSED_EVENTS : versions
  PROCESSED_EVENTS ||--o{ EVENT_RECEIPTS : deliveries
  TENANTS ||--o{ RECONCILIATION_RUNS : owns
  RECONCILIATION_RUNS ||--o{ SNAPSHOT_STAGING : stages
  RECONCILIATION_RUNS ||--o{ RECONCILIATION_AUDIT : records
  TENANTS ||--o{ OUTBOX : publishes
  TENANTS ||--o{ MESSAGE_INBOX : deduplicates
  EMPLOYEES ||--o{ BOOKINGS : reserves
  BOOKING_CABS ||--o{ BOOKINGS : allocates
  BOOKINGS ||--|| SEAT_ALLOCATIONS : has
  TENANTS ||--o{ APP_USERS : authenticates
```

Flyway V1..V9 are authoritative; Hibernate only validates. All employee/reference/booking identities include tenant. Unique HR identity is `(tenant_id,external_employee_id)`; canonical version is `(tenant_id,external_employee_id,source_version)`; event delivery key is `(tenant_id,event_id)`; inbox key is `(tenant_id,consumer,event_id)`. Composite office, manager, booking and cab foreign keys prevent cross-tenant associations. Seat allocations additionally bind the booking's cab.

Indexes support employee active/manager/office lookups, staging identity and valid buckets, pending outbox scans, and confirmed future bookings. Snapshot staging intentionally permits duplicate external IDs so every input row can be retained and duplicates detected across batches. Invalid rows do not store rejected field values. Reconciliation reports/audits contain counts, safe identifiers, approval evidence and codes rather than personal employee attributes.

See [DESIGN.md](DESIGN.md) for transaction ordering, semantics, cache coherence and failure recovery; [docs/audit.md](docs/audit.md) for findings and verified limitations.

```mermaid
sequenceDiagram
  participant Operator
  participant API
  participant File as Durable CSV volume
  participant DB as PostgreSQL
  participant Recovery
  Operator->>API: Create run with exact checksum / cutoff
  API->>DB: Persist run
  Operator->>API: Stream CSV
  API->>File: Bound size, hash bytes, atomic verified artifact
  loop Fixed-size batches
    API->>DB: Check lease token, insert staging, validate references, commit
  end
  Note over API,Recovery: If process dies, expired lease can be reclaimed
  Recovery->>File: Recheck checksum
  Recovery->>DB: Clear old staging and restage same run
  API->>DB: Duplicates / per-row versions / bucket diff / safety brake
  API-->>Operator: READY or BLOCKED report; no employee mutation
  Operator->>API: Approve exact run/checksum/revision; explicit override if blocked
  API->>DB: Lock tenant; verify roster/policy review; persist approval
  Operator->>API: Apply approved run
  API->>DB: Lock tenant; revalidate approval evidence
  API->>DB: One transaction: guarded HR changes + audit + outbox + COMPLETED
  API-->>Operator: Completed, or REVIEW_REQUIRED without employee changes
```
