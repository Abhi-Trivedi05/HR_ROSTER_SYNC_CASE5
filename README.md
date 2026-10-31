# MoveInSync HR Roster Sync

Java 21 / Spring Boot 3.5.16 modular monolith with PostgreSQL, JPA/JDBC, Flyway, RabbitMQ, Redis, JWT/RBAC, Micrometer and Actuator.

Implemented capabilities: independently validated event batches; durable event replay/version records; transactional employee/outbox writes; streaming CSV staging and bucketed diff; checksum-bound approval and explicit safety-brake override; atomic snapshot application; publisher leases/confirms/backoff/dead records; consumer inbox and transactional booking/seat cancellation; tenant-scoped access and coherent versioned caches.

**Verification status is recorded in [BENCHMARK_RESULTS.md](BENCHMARK_RESULTS.md) and [docs/verification.md](docs/verification.md). Do not treat implemented infrastructure tests as passing until their actual execution is recorded.**

## Start

```shell
docker compose up --build
```

Requires Docker with Linux containers and Compose. This starts the application on [localhost:8080](http://localhost:8080), PostgreSQL, authenticated Redis, durable RabbitMQ, and named volumes for database, broker and immutable snapshot files. Health: `/actuator/health`. RabbitMQ management: [localhost:15672](http://localhost:15672), local user `roster`, password `local-rabbit-only` unless overridden.

Compose intentionally activates the **local demonstration profile** so the project runs without an external identity provider. It creates a fixture tenant, office and four BCrypt-hashed users. Default password: `LocalDemoOnly!2026`. Set `DEMO_PASSWORD`, `DB_PASSWORD`, `RABBIT_PASSWORD`, and `REDIS_PASSWORD` before initial startup to override local defaults. Existing user hashes are not silently rotated on restart. These are local development defaults; they are not production credentials.

Tenant: `11111111-1111-4111-8111-111111111111`. Login usernames use this tenant UUID followed by `:hr`, `:admin`, `:operator`, or `:employee`. Demo employee internal ID: `22222222-2222-4222-8222-222222222222`. Tenant-qualified username is only an untrusted credential lookup selector; authorization identity is constructed from the verified database user after BCrypt verification.

```shell
curl -X POST http://localhost:8080/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"11111111-1111-4111-8111-111111111111:hr","password":"LocalDemoOnly!2026"}'
```

Copy `accessToken` into `TOKEN`. See [API_EXAMPLES.md](API_EXAMPLES.md) for HR, admin, operator and employee flows. Local RS256 tokens expire in 15 minutes; signing keys are regenerated on restart, invalidating existing local tokens. For production, disable `security.local.enabled`, use a non-demo profile and configure `JWT_ISSUER`, `JWT_JWK_SET_URI`, and `JWT_AUDIENCE` for your identity provider. Production should use durable IdP signing keys, TLS, network-restricted Redis/RabbitMQ, managed secrets, protected encrypted snapshot volumes, and separate migration/application database credentials. PostgreSQL extensions are installed by Flyway; provision `pgcrypto` through your DBA if the migration role cannot create it.

## Build and verify

Requires Java 21 and Maven 3.9+:

```shell
mvn test
mvn verify
mvn -Pbenchmarks -Dit.test=BenchmarkIT verify
```

`test` runs unit/MVC/security/cache/CSV tests. `verify` additionally runs real PostgreSQL Testcontainers, RabbitMQ, Redis, network failure and forked-JVM crash tests. Docker absence causes failure, never an automatic skip. Benchmarks are explicitly opt-in and run with a 512 MiB test heap; the snapshot benchmark asserts its 60-second target after writing its actual result. Results go to `benchmark-output/*.json`; `progress.json` is only incomplete-run progress, not a benchmark result. Normal CI excludes expensive benchmark-tagged tests and runs all functional tests with Docker.

For explicitly selected PostgreSQL-only tests, an existing real PostgreSQL may be supplied:

```shell
mvn -Droster.it.jdbcUrl=jdbc:postgresql://127.0.0.1:55432/postgres \
  -Droster.it.username=postgres \
  -Dit.test=EventIngestionIT,ReconciliationIT,BookingCancellationIT,SecurityIT,OutboxIT verify
```

This is an explicit separate execution mode, not an automatic fallback and not evidence that RabbitMQ/Redis/Testcontainers tests passed. Use a dedicated test database; tests create fixture data and apply migrations. `roster.it.password` supplies its password if needed. BenchmarkIT supports the same explicit PostgreSQL option. The default path always starts PostgreSQL through Testcontainers.

## Structure

Packages under `com.moveinsync.roster`:

- `ingestion`: DTOs, validation, canonical hashes, independent batch results, atomic ordered event processing.
- `employee`: read DTOs/scoped repositories; a separate transport service with transport-only optimistic revision.
- `snapshot`: immutable artifact storage, bounded CSV parsing, JDBC staging, hash buckets, diff, safety policy, approval, atomic application and leased restart recovery.
- `outbox`: durable PII-free messages, leased claims, confirmed RabbitMQ publication and operator requeue.
- `booking`: sample JPA models, safe reservation, inbox deduplication, cancellation and seat release.
- `cache`, `office`, `tenant`: version-aware cache-aside, authoritative metadata validation and scoped configuration.
- `config`, `shared`: JWT/BCrypt, role/tenant guards, safe errors, correlation IDs and transaction infrastructure.

Controllers only handle transport concerns and delegate. Services enforce authorization and transaction boundaries. SQL explicitly maps fields; no generic property copying or JPA entity REST serialization is used. Monetary values are not part of this model; future fare fields must use BigDecimal.

[DESIGN.md](DESIGN.md) explains ownership, ordering, locking, approvals and trade-offs. [ARCHITECTURE.md](ARCHITECTURE.md) contains Mermaid diagrams and the schema map. [docs/audit.md](docs/audit.md) records engineering findings and limitations.
