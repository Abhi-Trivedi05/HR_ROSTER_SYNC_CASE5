# Design decisions and transaction guarantees

## Scope and ownership

One deployable and one PostgreSQL database preserve local ACID transactions. RabbitMQ is an asynchronous boundary for side effects, not a replacement for the source database. JPA maps employee/reference/sample booking models; JDBC handles explicit field updates, version records, staging and set-based reconciliation. Controllers never own business logic. All identities in business API bodies exclude tenantId. Verified JWT claims establish tenant and, for employee access, internal employee ID.

HR event employeeId is the external HR identifier (1..80 characters). managerId is an optional internal UUID in the same tenant. CREATE and UPDATE are full HR images and both can upsert an unknown employee, allowing an UPDATE to arrive before a delayed CREATE. They require name, email, officeCode and boolean active; phone and manager are nullable. DEACTIVATE requires no data and changes lifecycle state without erasing HR profile or transport preferences.

HR owns name, email, phone, manager, office and active. Admin owns pickupPoint and specialAssistance. Admin DTOs cannot bind sourceVersion, active or HR fields. Legacy directory CREATE/HR PUT endpoints were removed to avoid unversioned HTTP writes bypassing ingestion. Transport SQL only assigns transport columns, transport_revision, global revision and updated_at. HR SQL never assigns transport columns. HR modification time is tracked separately as hr_updated_at.

Direct authenticated HR DEACTIVATE events apply immediately. Snapshot-driven missing/explicit-inactive changes cannot apply before approval. This is the later ingestion request's lifecycle behavior; it supersedes the previous design's conservative proposal to defer direct HR exits until snapshot approval.

## Ordering and idempotency

Canonical encoding is UTF-8 with a signed big-endian four-byte length before each field; -1 distinguishes null from empty. Input DTO strings are trimmed, UUIDs normalized by their type, booleans encoded as true/false, and textual case preserved. Event SHA-256 covers external ID, source version, operation and canonical HR data; eventId and source timestamp are delivery/audit metadata and excluded. HR state hashes cover name, email, phone, manager, office and active only. PostgreSQL's `roster_hr_hash` uses the same encoding; a real database test compares it with Java.

`event_receipts` has primary key `(tenant_id,event_id)`. `processed_events` has primary key `(tenant_id,external_employee_id,source_version)`. Splitting delivery receipts from canonical version records permits different event IDs with an identical version payload without weakening either uniqueness constraint.

Processing order:

1. Validate structure; acquire tenant transaction and row lock.
2. Existing eventId + same hash: DUPLICATE, no writes. Reused eventId + different hash: EVENT_ID_CONFLICT.
3. Existing canonical version + same hash: DUPLICATE; record a new delivery alias if its eventId is new. Conflicting hash: VERSION_CONFLICT.
4. Unseen older version: STALE, record its receipt/outcome but do not change employee/outbox.
5. Equal version from a snapshot/legacy row without an event record: compare HR state; record equivalent receipt or reject conflict.
6. Validate authoritative office and manager references, apply newer state, increment tenant HR revision, insert outbox + canonical version + delivery receipt, commit together.

An exact replay does not change employee fields, audit timestamps, revisions, version records, receipts or outbox. Locks may still cause PostgreSQL internal bookkeeping; the guarantee concerns business rows, not zero physical database I/O. New delivery aliases and first stale deliveries create bookkeeping only. Duplicates/stale events never create outbox entries.

## Isolation and locking

Every event runs in its own REQUIRES_NEW READ COMMITTED transaction. `SELECT id FROM tenants WHERE id=? FOR UPDATE` is taken before reading event/version/employee state. It locks even when no employee row exists, preventing simultaneous first-create races. Employee row reads also use FOR UPDATE; unique constraints provide an independent backstop. PostgreSQL READ COMMITTED takes a fresh snapshot for each statement after a lock wait, so the next transaction observes the preceding committed source version. No check/update gap is left outside this lock. [PostgreSQL isolation reference](https://www.postgresql.org/docs/16/transaction-iso.html).

The same tenant lock is used by HR mutations, transport writes, booking reservation/cancellation, reference mutations and approved snapshot apply. This deliberately sacrifices within-tenant write concurrency for a simple consistent lock order and protects manager cycles, roster review and booking/seat accounting. Different tenants can proceed independently. Tenant-level contention is a known scaling boundary; benchmarks must expose it. A later optimization may use per-employee/advisory locks and a separate manager-graph lock only after preserving the full test matrix.

PostgreSQL connect/read/cancel timeouts are bounded (5/90/2 seconds); the long read limit accommodates atomic snapshot statements while still detecting black-holed connections. The network-fault integration fixture lowers this read limit to three seconds. [pgJDBC timeout semantics](https://jdbc.postgresql.org/documentation/use/?lang=en).

Transport optimistic concurrency compares expectedRevision with transport_revision, not HR sourceVersion or the global DTO revision. HR activity cannot invalidate an otherwise fresh transport edit. The response supplies both revisions. Competing transport editors still receive 409. Immutable cache versions use the global revision.

`TenantTransactions` sets transaction-local `app.tenant_id`. All business SQL predicates and foreign keys include tenant; employee JPA identity is `(tenantId,id)`, so generated identity updates include tenant too. Tenant enumeration by trusted schedulers and Flyway metadata are explicit control-plane operations. RLS is considered but not enabled: enabling it safely also requires non-owner application credentials and transaction-local tenant setup on all JPA read transactions. Setting a session variable alone is not a claim of enforced RLS.

## CSV, approval and recovery

Upload is capped at 128 MiB, records at 8 KiB and rows at 200,000. JDBC staging batches default to 5,000 and are configurable from 100 to 5,000; functional crash tests explicitly use 1,000. This fixed bound is independent of total CSV size. UTF-8 decoding rejects malformed bytes. RFC4180 parsing handles quoted separators/escaped quotes/newlines while the wrapper bounds complete logical records. Strict headers prevent silent schema shifts. Raw uploaded bytes are SHA-256 checked against run metadata before immutable storage. No entire CSV or employee-ID set is held in JVM memory.

Stages commit independently under a run lease/token. Invalid rows store safe codes and row numbers, not rejected values. SQL marks duplicates across all batches. JVM space is O(batchSize * maximumRecordBytes); staging/diff work may use O(N) PostgreSQL disk and work_mem spill. SHA-256 identity buckets partition comparisons into 256 indexed groups. HR hashes exclude transport fields.

Each CSV row supplies its own employee source version. A run's sourceVersion identifies the export sequence only; it never advances employee versions. This avoids assigning a high version to every employee and incorrectly discarding later valid events from employees with smaller counters. Diff yields NEW, UPDATED, UNCHANGED, MISSING and INVALID counts. Same-version/different-state is a non-overridable conflict. Rows with newer live employee versions are preserved. Missing candidates require active state, hr_updated_at <= sourceCutoff, and absence from valid staging. Missing deactivation retains the last HR version/event ID rather than inventing a source version; its audit/outbox has a distinct run/change identity. A later genuinely newer HR event can reactivate the employee. Empty or invalid/duplicate-row snapshots are INVALID and cannot be approved; ordinary short files reach the safety brake. A known-full-file checksum catches transport truncation; a sender-provided checksum of an already truncated but syntactically valid export can only be detected through roster comparison/policy.

Default missing threshold is 0.30, configurable globally and per tenant, with exact integer/decimal product comparison (>= blocks). A blocked run applies nothing. Reports retain expected/incoming/missing counts and the fraction. Operator approval must carry exact run ID, checksum and report revision, with explicit overrideSafetyBrake for BLOCKED. Approval does not itself apply changes. Applying requires persisted APPROVED state and exact approved/expected checksum agreement.

Tenant HR/config revisions bind review to current roster and policy. An HR/reference/policy change invalidates approval and returns REVIEW_REQUIRED with a refreshed report, requiring another review. A transport-only edit does not invalidate HR approval. Apply holds the tenant lock and uses a single set-based database transaction for all employee changes, PII-free audit entries, outbox rows and COMPLETED state. Failure rolls back the entire application. Repeated COMPLETED apply is a no-op.

Durable CSV storage is a named volume. A crashed STAGING lease is reclaimable; a recovery worker verifies checksum and restages from byte zero into the same run, clearing old staging rows first. This favors deterministic recovery over a complex resumable CSV offset format. Partial upload files are never approved; an upload that died before durable artifact completion must be reuploaded. Automatic staging recovery is capped at five attempts; terminal/invalid runs require operator action. Retention/backup policy for PII-containing artifacts must be set by deployment operations; no background deletion can remove an in-progress run.

## Outbox and cancellation

Outbox records contain tenant/internal employee/event IDs, event type, source version and effective database transaction time only. Source event timestamp is retained for audit, but sourceVersion determines order. Lifecycle cutoffs use database time to avoid source clock skew. No names, email, phone, pickup address or raw event payload cross the broker.

Relay claims use a short transaction with FOR UPDATE SKIP LOCKED and a random fencing token. Claims expire after 60 seconds. Publication occurs outside the database transaction with persistent delivery, mandatory routing, correlated publisher confirm and a bounded confirm wait. An unroutable returned message or negative/missing confirm is failure. Completion/failure updates require the exact claim token. Expired exhausted claims are DEAD; transient failures use capped exponential backoff and max attempts. Operators can requeue a durable DEAD record with the same event ID. [Spring AMQP confirm/return semantics](https://docs.spring.io/spring-amqp/reference/amqp/template.html).

Crash after DB commit/before publish leaves PENDING. Crash after broker acceptance/before marking PUBLISHED can republish the same event. This is **at least once**, not exactly once: no distributed atomic commit spans PostgreSQL and RabbitMQ. Consumer inbox `(tenant,consumer,eventId)` and message hash make business effects idempotent.

Booking cancellation inserts inbox, locks eligible bookings, marks allocations released, decrements cab allocation counters and records cancellation in one transaction. Any failure rolls everything back for retry. Already released allocations cannot decrement twice; a missing allocation/accounting mismatch fails loudly and safely. Only confirmed bookings starting after deactivation with source_version_at_booking <= the deactivation version are cancelled. Reservations capture the employee's authoritative HR version, so later rehire bookings survive delayed old messages without relying on wall-clock creation ordering. Booking reservation uses the same lock and rechecks active state, so it cannot create a new booking while an employee is inactive. Consumer failures do not roll back the employee's independently committed deactivation. Consumer retries exhaust into a durable RabbitMQ dead queue; after fixing data/dependency issues, replay the same message/event ID.

## Cache, security and observability

Employee reads first query authoritative global revision, then access `tenant:employee:UUID:revision`; full profile is loaded only on a miss. Old keys cannot be selected after a committed revision change, even if invalidation failed or races with refill. Pointer-based invalidation deletes the latest exact key; obsolete versions expire at configured TTL (default 60 seconds). Office fingerprints and tenant config revisions are checked against authoritative metadata before using cached values. This costs a DB authority read for strict freshness; Redis is never authoritative for eligibility or version ordering. Redis outage falls back to the database with 300 ms connection/read timeouts.

JWT RS256 validation covers signature, issuer, audience, expiry, not-before and tenant UUID; roles come from signed claims. EMPLOYEE can read only its employee_id profile and reserve its own booking; ADMIN manages only transport fields; OPERATOR reviews/approves/configures; HR_INTEGRATION submits HR events. Both HTTP security and service guards enforce roles and tenant matching. BCrypt cost 12 hashes local credentials; local demonstration keys/users must not be enabled in production. Unknown JSON properties and scalar type coercion are rejected to prevent mass assignment and fractional/string versions.

Logs use structured ECS JSON and a generated correlation ID. Event logs include tenant ID, a SHA-256 pseudonym of the external employee ID, event type, source version and processing status. Raw input IDs may contain personal values, so they are not logged verbatim. DTO string forms are redacted. SQL bind logging and message-body error logging are disabled/replaced. Standard error bodies include errorCode, safe message, correlationId and timestamp; stack traces and invalid values are not returned.

Metrics include received/applied/rejected/duplicate/stale/retryable events, outbox backlog/publish/poll failures, reconciliation duration/missing fraction/blocked runs, cancellations, cache hit/miss/failures and hit ratio, plus standard HTTP/JVM/pool metrics. No employee/tenant identifiers are metric labels. Backlog is refreshed by the enabled publisher; it is not a continuously exact transaction counter.
