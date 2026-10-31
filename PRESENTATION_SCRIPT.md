# MoveInSync HR Roster Sync — presentation and demonstration script

Presenter: Abhi Trivedi  
Suggested duration: 15–20 minutes, followed by questions.  
Repository: https://github.com/Abhi-Trivedi05/HR_ROSTER_SYNC_CASE5

Read the quoted sections aloud. Code blocks are commands to demonstrate. All employee examples below are synthetic. Do not display JWTs, raw employee data, or credentials in a recording.

## 1. Introduction — 1 minute

> “Hello, I am Abhi Trivedi. This project implements the MoveInSync HR Roster Sync case study. It keeps the transport employee directory synchronized with HR while handling duplicate events, events arriving out of order, and incomplete nightly files.
>
> The main correctness goals are that old data cannot overwrite new data, transport preferences remain protected, snapshot changes require approval, and an employee exit reliably cancels future bookings even if messaging is temporarily unavailable.”

## 2. Architecture — 2 minutes

Open [ARCHITECTURE.md](ARCHITECTURE.md) to show the architecture, sequence and schema diagrams.

> “I chose a modular monolith: one Spring Boot deployment with separate packages for employees, ingestion, snapshots, outbox, bookings, security and caching. This keeps deployment simple and allows employee changes and outbox messages to commit in one database transaction.
>
> The stack is Java 21, Spring Boot 3, Spring Security with JWT, PostgreSQL, Flyway, JPA and JDBC. JPA maps domain entities; JDBC gives explicit control over locking, field updates, staging batches and set-based reconciliation. Redis accelerates reads, and RabbitMQ carries asynchronous downstream notifications.
>
> Controllers handle HTTP mapping and validation. Services enforce business rules and permissions. Repositories provide tenant-scoped persistence. REST responses use DTOs, never JPA entities. Constructor injection keeps dependencies explicit and business classes testable.”

## 3. Data model and ownership — 1 minute

> “A tenant owns offices, employees and reconciliation runs. Employee identity has an internal UUID plus an external HR employee ID. The database enforces uniqueness on tenant and external employee ID. Manager references must remain within the tenant.
>
> HR controls name, email, phone, manager, office and active state. Transport admins control pickup point and special assistance. Separate DTOs and services explicitly assign only permitted columns. Unknown request properties are rejected, preventing mass assignment.
>
> HR sourceVersion is separate from transportRevision. An HR update therefore cannot invalidate an otherwise current transport edit or overwrite transport preferences. Flyway owns schema changes; Hibernate does not generate the schema.”

## 4. Event ordering and transaction safety — 2 minutes

> “Each event includes eventId, employeeId, source version, event type, timestamp and HR data. Each batch record is validated independently, so an invalid phone, email, office or version does not roll back valid neighboring records.
>
> The source version determines order; the source timestamp is audit metadata. Versions five, seven and six leave the employee at seven. Replaying the same event is a no-op. An identical payload with the same version is also a duplicate, while a different payload at the same version is a VERSION_CONFLICT.
>
> A deterministic SHA-256 hash represents canonical HR data. A delivery receipt is unique by tenant and event ID. A canonical version record is unique by tenant, external employee ID and source version.
>
> Every event runs in its own READ COMMITTED transaction. The processor locks the tenant row before inspecting event and employee state, and also locks employee rows. This covers concurrent first creation, where there is no employee row to lock yet. Employee updates, receipts, canonical version records and outbox writes commit together.
>
> The trade-off is reduced write concurrency within a tenant. Different tenants can progress independently. I chose this simple lock order for correctness, measured its cost, and documented finer-grained locking as future work.”

Clarification if asked: exact event replay causes no business-row changes. A previously unseen stale delivery may add receipt bookkeeping, but never changes the employee or creates an outbox event. A new event ID carrying an identical known version may add a delivery alias.

## 5. Snapshot reconciliation and safety brake — 2 minutes

> “Nightly snapshots support up to 200,000 rows without loading the full CSV into JVM memory. The parser streams bounded records into PostgreSQL staging through configurable JDBC batches. The default batch size is 5,000. Identity hashes divide comparison into 256 buckets, and HR state hashes exclude transport fields.
>
> The diff reports NEW, UPDATED, UNCHANGED, MISSING and INVALID. Duplicate identifiers, malformed input and conflicting same-version data cannot silently produce an approved snapshot. Each row has its own employee source version; the run export sequence does not replace it.
>
> The default missing threshold is thirty percent and can be configured per tenant. Reaching the threshold marks the run BLOCKED and applies nothing. An operator must inspect the report and explicitly override the brake before applying a blocked run. Invalid snapshots cannot be overridden.
>
> Approval is tied to the exact run, file checksum and report revision. A roster or policy change can require review again. Approval and application are separate operations; snapshot-driven deactivation never occurs before approval. Application is atomic, so failure cannot leave half of a snapshot applied.
>
> Durable snapshot storage and expiring staging leases support recovery after a process crash. Recovery checks the checksum and restages the file from the beginning using bounded memory.”

Important scope distinction: authenticated direct HR DEACTIVATE events apply immediately. The approval requirement governs snapshot application, including missing-employee deactivation.

## 6. Reliable messaging and bookings — 1 minute

> “Every applied employee change writes a PII-free outbox message in the same transaction. A scheduled publisher claims rows with FOR UPDATE SKIP LOCKED and fenced leases, then publishes persistent messages with broker confirms. Transient failures use exponential backoff; exhausted messages remain available for operator recovery.
>
> If the process crashes after the database commit, the outbox remains pending. If it crashes after broker acceptance but before recording publication, it may publish again. Therefore this is at-least-once delivery, not exactly-once transport.
>
> The consumer uses an inbox uniqueness constraint to deduplicate business effects. Booking cancellation, allocation release and inbox completion share a transaction. A failure rolls them all back for safe retry. A downstream outage cannot roll back the independently committed employee exit. Booking source versions also protect later rehire bookings from delayed old messages.”

## 7. Security, cache and operations — 1 minute

> “JWT authentication establishes tenant and role. HR_INTEGRATION submits events, ADMIN manages transport fields, OPERATOR reviews snapshots, and EMPLOYEE accesses their own profile and reservations. Both HTTP security and service authorization enforce these boundaries. Local demo passwords are BCrypt-hashed.
>
> Tenant IDs come from the authenticated identity, never the business request body. Tenant predicates and composite references protect persistence. PostgreSQL row-level security is documented as possible additional protection; it is not enabled in this implementation.
>
> Redis uses tenant- and revision-aware cache keys, TTLs and update invalidation. The database remains authoritative, including when Redis is unavailable. Micrometer exposes event outcomes, outbox health, reconciliation, booking and cache metrics.
>
> Structured logs include correlation and safe identifiers but omit names, emails, phone numbers, addresses, raw events and JWTs. Standard errors contain errorCode, a safe message, correlationId and timestamp.”

## 8. Deliverables to show — 1 minute

| Deliverable | File or directory | What to explain |
| --- | --- | --- |
| Startup and overview | [README.md](README.md) | One-command startup, demo identity and prerequisites |
| Design decisions | [DESIGN.md](DESIGN.md) | Transactions, locking, ownership, recovery and limitations |
| Architecture and schema | [ARCHITECTURE.md](ARCHITECTURE.md) | Mermaid architecture/sequence diagrams and model relationships |
| API contracts | [API_EXAMPLES.md](API_EXAMPLES.md) | Roles, bodies, responses and error semantics |
| Database schema | `src/main/resources/db/migration/` | Nine versioned Flyway migrations |
| Application and tests | `src/main/java/`, `src/test/java/` | Modular layering and real database/failure tests |
| Test evidence | [docs/verification.md](docs/verification.md), [unit output](docs/test-results/unit-full.json), [PostgreSQL output](docs/test-results/postgresql-full.json) | Recorded execution, suite names, cases and totals |
| Engineering audit | [docs/audit.md](docs/audit.md) | Findings, fixes and remaining trade-offs |
| Performance evidence | [BENCHMARK_RESULTS.md](BENCHMARK_RESULTS.md), `docs/benchmarks/` | Measured results, raw JSON and measurement limitations |
| Containers | [Dockerfile](Dockerfile), [compose.yml](compose.yml), [.dockerignore](.dockerignore) | Non-root app, healthchecks, resource limits and persistent volumes |
| Automated verification | [.github/workflows/verify.yml](.github/workflows/verify.yml), [tools/compose_smoke.py](tools/compose_smoke.py) | Maven verification plus real four-service HTTP/message smoke |
| Presentation | [PRESENTATION_SCRIPT.md](PRESENTATION_SCRIPT.md) | This talk, demo plan, evidence and questions |

## 9. Test output and benchmark results — 2 minutes

> “I distinguish executed results from tests merely present in the source. Archived local results show 35 unit/MVC tests and 36 selected real-PostgreSQL integration tests, each with zero failures, errors or skipped tests. These local PostgreSQL tests are not described as Testcontainers runs.
>
> The later GitHub Docker-enabled run passed the default Maven verification and separately built and started all four Compose services. Its live smoke verified authentication, HR/transport ownership, Redis population, snapshot approval and application, RabbitMQ delivery, booking cancellation and seat release.”

Open [successful CI run 41188266387](https://github.com/Abhi-Trivedi05/HR_ROSTER_SYNC_CASE5/actions/runs/41188266387). The tested application commit was `f3f1fbfc016b84895bf6c8aafce4f4ad8444bd43`. Show the green `verify` and `compose-smoke` jobs and their downloadable reports. Do not invent a combined CI test count from the separate archived local totals.

Archived output summary, transcribed from the committed JSON files:

```text
Local unit/MVC:       tests=35  failures=0  errors=0  skipped=0
Local PostgreSQL:    tests=36  failures=0  errors=0  skipped=0
GitHub verify:       success (Docker-enabled default Maven verification)
GitHub compose-smoke: success (actual image build, startup and live smoke)
```

Coverage includes valid and invalid events, mixed batches, versions 5/7/6, same-event 20-thread races, concurrent versions, ownership, tenant isolation, role restrictions, JWT failures, snapshot safety/approval, crash recovery, outbox retry/deduplication, booking rollback, cache invalidation and PII-safe errors/logs. See the archived JSON for individual case names and CI artifacts for the container execution output.

| Executed native PostgreSQL benchmark | Time | Throughput | p50 / p95 / p99 ms | Sampled peak JVM heap | JDBC execute/batch calls |
| --- | ---: | ---: | --- | ---: | ---: |
| 200,000-row snapshot | 58.67 s | 3,408.91 rows/s | 419.130 / 552.523 / 1,665.088 | 193.35 MiB | 582 |
| 200,000 event ingestion operations | 676.41 s | 295.68 events/s | 3.176 / 4.642 / 5.880 | 86.57 MiB | 2,200,000 |
| 20 threads, 20,000 update requests | 57.13 s | 350.08 requests/s | 53.708 / 72.024 / 105.433 | 86.63 MiB | 219,400 |

> “The final snapshot run met the sixty-second target at 58.67 seconds on the documented laptop, with a 512 MiB test heap. This is an observed run, not a production SLA. An earlier 70.89-second failed baseline is preserved. Snapshot percentiles describe staging batches, not complete reconciliation runs. Heap is sampled, and JDBC execute counts are not PostgreSQL row counts or network round trips. These benchmarks used native PostgreSQL with cache and messaging disabled, so they do not measure end-to-end HTTP or broker throughput.”

## 10. Run the application and demonstrate — 5 minutes

### Prerequisite and current status

Install/start Docker Desktop with Linux containers and Docker Compose v2. On Windows, its supported virtualization/WSL setup must also be available. No host Java, Maven, database, broker, API key or external identity provider is needed for the default demo.

On this local Windows machine, the startup attempt on 2026-10-31 failed because `docker` is not installed/on PATH; the standard Docker Desktop executable path was also absent. The application is **not currently running locally**. This does not replace the separately recorded successful GitHub Docker execution.

From the repository folder in PowerShell:

```powershell
docker compose up --build --wait --wait-timeout 240
docker compose ps
Invoke-RestMethod http://localhost:8080/actuator/health
```

Expected health body when healthy: `{"status":"UP"}`. This is expected output, not a claim of a fresh local run. First build may take several minutes. To inspect startup failures use `docker compose logs --tail 100 app` and `docker compose ps`.

> “This command builds the Java image and starts the API, PostgreSQL, Redis and RabbitMQ. Healthchecks gate startup. Flyway applies migrations automatically. The local demo seeds a tenant, office, employee, cab and four role-specific users. Named volumes preserve data across restarts.”

The default local password is `LocalDemoOnly!2026`; use it only with this demo. Tokens expire after fifteen minutes and local signing keys rotate on application restart. This is a backend API project, so opening `/` does not show a frontend dashboard.

### Login and demonstrate event order in PowerShell

Run in one PowerShell session. Keep tokens in variables and do not print them.

```powershell
$base = 'http://localhost:8080'
$tenant = '11111111-1111-4111-8111-111111111111'
function Get-DemoToken([string]$role) {
    $body = @{username="${tenant}:$role"; password='LocalDemoOnly!2026'} | ConvertTo-Json
    (Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/token" -ContentType 'application/json' -Body $body).accessToken
}
$hr = @{Authorization="Bearer $(Get-DemoToken 'hr')"}
$admin = @{Authorization="Bearer $(Get-DemoToken 'admin')"}
$operator = @{Authorization="Bearer $(Get-DemoToken 'operator')"}
$employeeId = "PRESENTATION-$([guid]::NewGuid().ToString('N').Substring(0,8))"
$data = @{name='Demo Person'; email='presentation@example.invalid'; phone='+919876543210'; managerId=$null; officeCode='BLR'; active=$true}
function Send-HrEvent($event) {
    Invoke-RestMethod -Method Post -Uri "$base/api/v1/hr/events" -Headers $hr -ContentType 'application/json' -Body ($event | ConvertTo-Json -Depth 6)
}
function New-HrEvent([long]$version, [string]$kind) {
    @{eventId=[guid]::NewGuid().ToString(); employeeId=$employeeId; version=$version; eventType=$kind; timestamp=[DateTimeOffset]::UtcNow.ToString('o'); data=$data.Clone()}
}
$v5 = New-HrEvent 5 'CREATE'
Send-HrEvent $v5
$v7 = New-HrEvent 7 'UPDATE'
Send-HrEvent $v7
Send-HrEvent (New-HrEvent 6 'UPDATE')
Send-HrEvent $v7
$conflict = New-HrEvent 7 'UPDATE'
$conflict.data.name = 'Changed synthetic name'
Send-HrEvent $conflict
```

Expected statuses in order: APPLIED, APPLIED, STALE, DUPLICATE, REJECTED with VERSION_CONFLICT. These examples intentionally use unique external IDs so repeat presentations do not reuse earlier employee versions.

> “Version six cannot overwrite seven. Replaying the exact seventh-version event is a no-op. A different seventh-version payload is rejected.”

### Mixed batch validation

```powershell
$valid = New-HrEvent 8 'UPDATE'
$invalid = New-HrEvent 9 'UPDATE'
$invalid.data.phone = 'invalid-phone'
$batchJson = ConvertTo-Json -InputObject @($valid,$invalid) -Depth 6
Invoke-RestMethod -Method Post -Uri "$base/api/v1/hr/events/batch" -Headers $hr -ContentType 'application/json' -Body $batchJson
```

> “The first record applies, and the invalid record is rejected independently with a safe error code. The response does not echo the invalid phone.”

### Full-stack automated demonstration

For the snapshot/cache/booking portion, use the existing repeatable smoke helper on this **local demo stack only**. It needs Python 3 in addition to Docker. It changes fixture data, restores the seeded employee afterward and retains audit records.

```powershell
python tools/compose_smoke.py
```

The helper emits these success labels only after its assertions pass (expected labels, not a newly captured local transcript):

```text
PASS: health and JWT authentication for all four demo roles
PASS: HR ingestion, transport ownership and authenticated Redis caching
PASS: persistent snapshot storage, approval and atomic application
PASS: PostgreSQL outbox -> RabbitMQ -> inbox -> cancellation and seat release
PASS: full Compose smoke test
```

> “This exercises the real HTTP endpoints and backing services. It uploads a checksummed CSV, approves the specific report, applies it, creates a future booking and deactivates the employee. It then waits for the real outbox and RabbitMQ consumer and verifies cancellation and seat accounting in PostgreSQL.”

For an interactive snapshot demonstration, follow [API_EXAMPLES.md](API_EXAMPLES.md): create a run with the exact file SHA-256, upload CSV, inspect counts and revision, approve, then apply. Include **all active demo employees** in a normal-demo snapshot; a one-row file may correctly trigger the missing-employee brake. Do not override the brake merely to make a demo pass.

### Show monitoring and stop

```powershell
Invoke-RestMethod "$base/actuator/prometheus" -Headers $operator
docker compose logs --tail 30 app
docker compose down
```

`down` stops services and keeps named-volume data. Do not add `--volumes` unless deliberately deleting local data. Review logs before screen sharing; application business logs are designed to omit PII, but environment and third-party output still deserve care.

## 11. API reference for the presentation

All business endpoints use a bearer token and derive tenant from it. IDs in employee URLs are internal UUIDs; HR event employeeId is the external HR identifier.

| Method | Path | Role | Purpose |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/token` | Local demo login | Obtain JWT |
| POST | `/api/v1/hr/events` | HR_INTEGRATION | Process one event |
| POST | `/api/v1/hr/events/batch` | HR_INTEGRATION | Independently process 1–1,000 events |
| GET | `/api/v1/employees/{id}` | Own EMPLOYEE, ADMIN, OPERATOR | Read profile DTO |
| GET | `/api/v1/employees?page=0&size=50` | ADMIN, OPERATOR | Bounded directory listing |
| PATCH | `/api/v1/employees/{id}/transport` | ADMIN | Transport-only update with expected transport revision |
| GET | `/api/v1/offices/{code}` | ADMIN, OPERATOR, HR_INTEGRATION | Read office metadata |
| PATCH | `/api/v1/offices/{code}` | OPERATOR | Change office metadata |
| PATCH | `/api/v1/tenant/config` | OPERATOR | Configure missing threshold |
| POST | `/api/v1/reconciliations` | OPERATOR | Create checksum-bound run |
| PUT | `/api/v1/reconciliations/{runId}/csv` | OPERATOR | Stream CSV upload |
| GET | `/api/v1/reconciliations/{runId}` | OPERATOR | Inspect safe report |
| GET | `/api/v1/reconciliations/{runId}/errors` | OPERATOR | Page safe row error codes |
| POST | `/api/v1/reconciliations/{runId}/refresh` | OPERATOR | Refresh review |
| POST | `/api/v1/reconciliations/{runId}/approve` | OPERATOR | Approve checksum/revision; explicit brake override if needed |
| POST | `/api/v1/reconciliations/{runId}/apply` | OPERATOR | Atomically apply approved run |
| POST | `/api/v1/outbox/{eventId}/requeue` | OPERATOR | Retry DEAD outbox message |
| POST | `/api/v1/bookings` | EMPLOYEE | Reserve own seats |
| GET | `/actuator/health` | Public | Safe health status |
| GET | `/actuator/prometheus` | OPERATOR | Metrics |

Per-record event results use HTTP 200 with APPLIED, DUPLICATE, STALE, REJECTED or RETRYABLE. HTTP 200 alone therefore does not mean the event applied. Invalid top-level JSON gets 400. Unauthorized is 401, forbidden is 403, scoped missing resource is 404, and conflicting transport/run state is 409. Full payload contracts and examples are in API_EXAMPLES.md.

## 12. Likely questions and suggested answers

**Why not microservices?** Local database transactions simplify employee/outbox/snapshot consistency. Modules preserve separation without introducing distributed transactions or deployment overhead.

**What prevents duplicate creation from concurrent requests?** Tenant/employee locks serialize the decision, and unique constraints protect tenant/external ID, event receipt and canonical source version independently.

**Does a batch share one transaction?** No. Each record gets an independent transaction and result, preserving valid records when another is rejected.

**Why not exactly once?** PostgreSQL and RabbitMQ do not share an atomic commit. Broker acceptance can precede a process crash, causing republish. Inbox deduplication makes the effects idempotent.

**Can the safety brake be bypassed?** A BLOCKED run requires an authorized explicit override tied to its exact checksum/revision. INVALID runs cannot be overridden. No partial snapshot application is allowed.

**What happens during a cache outage?** Authoritative database reads continue; cache availability is not required for correctness. Revision-aware keys prevent serving an older cached profile after a committed revision change.

**What are the remaining production concerns?** Tenant-lock contention, longer atomic snapshot transactions, production IdP/secret/TLS configuration, separate migration/application DB roles, artifact retention/encryption/backups, and broader representative performance testing. RLS is not claimed as implemented. The measured snapshot margin is narrow.

**Why GitHub Actions?** It reproducibly runs tests on Java 21 with real Docker infrastructure and verifies the actual deployment image. It is not needed to run the application locally.

**Any external API key needed?** No external API key is needed for the local stack. JWTs authenticate application users; local default credentials are demonstration fixtures and must be replaced for deployment.

## 13. Closing — 30 seconds

> “The project focuses on correctness under duplicate delivery, reordered updates and failures. Database transactions and constraints protect the core state; approval protects bulk reconciliation; the outbox and inbox make asynchronous booking effects recoverable. The repository includes runnable containers, API contracts, test evidence, measured benchmarks, diagrams and documented trade-offs.”
