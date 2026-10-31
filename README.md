# MoveInSync HR Roster Sync

Java 21 / Spring Boot 3.5.16 modular monolith with PostgreSQL, JPA/JDBC, Flyway, RabbitMQ, Redis, JWT/RBAC, Micrometer and Actuator.

Implemented capabilities: independently validated event batches; durable event replay/version records; transactional employee/outbox writes; streaming CSV staging and bucketed diff; checksum-bound approval and explicit safety-brake override; atomic snapshot application; publisher leases/confirms/backoff/dead records; consumer inbox and transactional booking/seat cancellation; tenant-scoped access and coherent versioned caches.

**Verified:** both `mvn verify` and the complete Docker Compose build/start/smoke scenario passed in [GitHub Actions](https://github.com/Abhi-Trivedi05/HR_ROSTER_SYNC_CASE5/actions/runs/41188266387). Measurements and execution evidence are in [BENCHMARK_RESULTS.md](BENCHMARK_RESULTS.md) and [docs/verification.md](docs/verification.md).

## Start

```shell
docker compose up --build
```

Requires only Docker with Linux containers and Compose v2; no host Java, Maven, PostgreSQL, Redis, RabbitMQ, API key, `.env` file or external identity provider is needed for the default demo. The multi-stage Dockerfile builds the Java 21 JAR, then runs it as a non-root user in a smaller JRE image. Dependency downloads are cached between builds.

This starts the API on [localhost:8080](http://localhost:8080), PostgreSQL, authenticated Redis and durable RabbitMQ. PostgreSQL/Redis/broker connections stay on the private Compose network. Named volumes preserve the database, broker and immutable snapshot files across restarts. Health: [localhost:8080/actuator/health](http://localhost:8080/actuator/health). RabbitMQ management: [localhost:15672](http://localhost:15672), local user `roster`, password `local-rabbit-only` unless overridden.

For a single background command that waits until the application is ready:

```shell
docker compose up --build --wait --wait-timeout 240
```

Dependency healthchecks gate initial startup; the application readiness check waits for migrations, demo bootstrap and database readiness. Its readiness depends on PostgreSQL rather than RabbitMQ/Redis so a later broker/cache outage does not stop durable HR ingestion. The full health endpoint reports all dependency health. The app has a 1 GiB container limit, JVM container-aware heap sizing and graceful shutdown. Redis has a bounded 128 MiB LRU cache. First startup downloads build dependencies/images and takes longer than a cached restart.

Useful commands:

```shell
docker compose ps
docker compose logs -f app
docker compose down
```

`down` stops the stack and retains named-volume data. Avoid deleting volumes when you want to preserve employee/snapshot/booking data.

Compose intentionally activates the **local demonstration profile** so the project runs without an external identity provider. It creates a fixture tenant, office and four BCrypt-hashed users. Default password: `LocalDemoOnly!2026`. Set `DEMO_PASSWORD`, `DB_PASSWORD`, `RABBIT_PASSWORD`, and `REDIS_PASSWORD` before initial startup to override local defaults. Existing user hashes are not silently rotated on restart. These are local development defaults; they are not production credentials.

Tenant: `11111111-1111-4111-8111-111111111111`. Login usernames use this tenant UUID followed by `:hr`, `:admin`, `:operator`, or `:employee`. Demo employee internal ID: `22222222-2222-4222-8222-222222222222`; a four-seat demo cab is also provisioned as `55555555-5555-4555-8555-555555555555`. Tenant-qualified username is only an untrusted credential lookup selector; authorization identity is constructed from the verified database user after BCrypt verification.

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

CI also builds the actual Dockerfile, starts the complete Compose stack with `--wait`, and runs `python3 tools/compose_smoke.py` against the real HTTP API. That local-demo smoke covers four-role login, HR/transport ownership, actual Redis population, persisted CSV upload/approval/application, and RabbitMQ-driven booking cancellation/seat release. It restores the demo employee afterward and retains safe fixture/audit records; use it only on a local demonstration stack. Container status/logs are saved as CI artifacts. The smoke uses Python's standard library; Python is needed only for this optional verification helper, not for starting the app.

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
