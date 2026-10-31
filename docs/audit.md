# Engineering audit

This audit inspected implementation and executed tests. Final execution evidence is in [verification.md](verification.md); numerical results are in [BENCHMARK_RESULTS.md](../BENCHMARK_RESULTS.md). Infrastructure-dependent tests that could not execute are not called passing.

## Findings fixed during implementation

| Finding | Correction | Evidence |
| --- | --- | --- |
| Unversioned directory HR endpoints could bypass event ordering | Removed directory POST/HR PUT; HR writes enter ingestion | Controller contracts and security tests |
| JPA identity might permit generated updates without tenant predicate | Composite employee key and tenant-scoped repository projections | Mapping, scoped query inspection and cross-tenant integration tests |
| Concurrent first event has no employee row to lock | Lock tenant before receipt/version/employee reads; DB unique constraints | Real PG 20-thread same-event and versions 8/9 races |
| HR and transport writes could lose fields or invalidate each other's revision | Explicit SQL columns and independent transport_revision | Concurrent real PG ownership test |
| Transport updated_at could incorrectly change missing eligibility | Separate hr_updated_at | Snapshot predicate inspection and preservation tests |
| A global snapshot employee version could block future valid per-employee events | Required per-row CSV version; run version is export metadata only | Mixed-version snapshot regression test |
| Snapshot missing removal could invent HR source versions | Retain source version/event ID; separate run audit/outbox identity | Apply SQL and subsequent newer-HR regression |
| Approval could survive a changed roster/policy/file | Exact run/checksum/revision evidence, tenant HR/config review revisions | Threshold-change, newer-HR, checksum and run-isolation tests |
| One bad CSV row could masquerade as a missing employee | Invalid/duplicate snapshot cannot be approved | Empty/malformed/invalid/duplicate real PG tests |
| Failed apply could leave partial employees without outbox | Single approved-run transaction for employee/audit/outbox/state | Injected apply failure + retry |
| A crashed upload/stager could leave reusable partial approval | Immutable verified artifact, leased stages, no approval before complete validation | Durable-chunk recovery and forked-JVM crash fixtures |
| Old relay worker could acknowledge another worker's claim | Fencing token on complete/fail; expired exhausted claims become DEAD | Fenced-claim PG tests |
| Confirm without route could be mistaken for delivery | Mandatory RabbitMQ returns checked alongside correlated confirm | Broker integration test and publisher code |
| Broker acceptance before source mark can duplicate | Stable outbox event ID and transactional hashed inbox | Replay, expired-lease, broker duplicate and hard-process fixtures |
| Partial seat release could double-decrement capacity | Allocation flag + guarded counter + booking status + inbox in one transaction | Real PG failure/redelivery/cancellation tests |
| Clock-based booking creation cutoff skipped valid bookings on real PG | Capture HR version at reservation; compare to deactivation message version | Initially failing tests fixed; rehire-watermark regression |
| Eviction-only caches could serve stale values after race/outage | Authoritative revision/fingerprint check before immutable cache key lookup | Unit tests and Redis integration fixture |
| Exceptions/paths could echo submitted personal data | Constant safe errors and fixed ProblemDetail instance URI; no raw exception logs | MVC response tests and structured-log inspection |
| Default Rabbit error handlers can render message bodies | Custom safe error/recovery handlers | RabbitConfig and consumer inspection |
| External IDs can themselves contain personal information | Log SHA-256 pseudonym instead of raw external ID | Event logging mapping and JSON log scan |
| Benchmark Mockito probes retained per-event invocation history | Dedicated constant-memory no-op benchmark probe | BenchmarkProbeConfig; initial incomplete run excluded |
| Initial 1K staging snapshot exceeded 60 seconds | Configurable bounded 5K batches, measured rerun and phase timing | Baseline/raw benchmark result files |

## Requirement coverage

Event validation: DTO/Bean Validation, strict JSON types, email/phone, office/manager tenant checks, result per record, bounded JSON body, error codes and metrics. Batches use independent transactions. Version/event uniqueness and SHA-256 detection are database-backed; no Mockito-only concurrency proof is claimed.

Snapshots: streamed UTF-8 RFC4180 records, bounded row/file/batch limits, durable checksum-bound artifact/run, JDBC staging, duplicate detection, 256 hash buckets, SQL diff, decimal safety brake, invalid-run stop, explicit blocked override, persisted approval, guarded atomic apply, audit/outbox and restart recovery. Performance is measured separately from parser-only unit testing.

Outbox/booking: source transaction never depends on broker availability; relay leases/SKIP LOCKED/fencing/confirm/return/backoff/dead records; consumer hash/inbox, transactional allocation release and booking cancellation; sample reservation rejects inactive/capacity failures. Exactly-once broker delivery is not claimed.

Security/cache/operations: signed JWT checks, BCrypt, role/self/tenant guards, composite associations, DTO mapping, source-versus-transport separation, versioned Redis cache-aside, TTL/invalidation/fallback, safe JSON logs/errors/correlation IDs and low-cardinality Micrometer metrics.

## Limits requiring explicit interpretation

- Coarse tenant locking prioritizes consistency over single-tenant throughput. Report the measured limitation; do not present this as unlimited write scaling.
- Snapshot counters are per-row employee versions. Export sequence metadata is not an employee version. A malformed/empty file cannot override the brake; a syntactically complete short file requires deliberate operator judgment.
- A snapshot-induced deactivation at unchanged HR version cannot be undone by replaying an old HR event. Upstream must issue a genuinely newer employee version for a corrective HR update.
- PostgreSQL RLS is not enabled. Business predicates, service guards and composite keys enforce isolation; production RLS needs a separate reviewed role/transaction setup.
- The one-command Compose deployment is an explicit local demo. Production uses external identity, managed secrets/TLS and non-superuser application roles; the compose owner account and bootstrap passwords are not production defaults.
- Snapshot artifacts contain PII even though reports/outbox/logs do not. They require deployment-level access control, encryption, retention and backups. Multi-instance deployments must share the artifact store; local ephemeral disks are insufficient.
- Cache coherence deliberately requires an authoritative DB metadata read. During DB outage, stale cached profiles are not served.
- A full passing container suite and actual broker crash/network behavior still require Docker. An explicit local real-PostgreSQL run proves database behavior but does not prove Redis/RabbitMQ integration or Compose startup.
