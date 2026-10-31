# Executed verification - 2026-10-31

Host: Windows 11, Asia/Calcutta. Java 21.0.13.0.2 and Maven 3.9.9 were downloaded into ignored `.tools/`. Docker is not installed; WSL is also unavailable. An isolated PostgreSQL 16.10 binary fixture was initialized under `.tools/pgdata-native`, bound to 127.0.0.1:55432, and used explicitly for the PostgreSQL-only tests and benchmarks. This is not described as a Testcontainers run.

## Actual results

| Execution | Actual result |
| --- | --- |
| Java 21 main/test compilation and Spring Boot JAR packaging | Passed |
| Full unit/MVC/crypto/cache/CSV/metrics suite | 35 tests passed; 0 failures/errors/skips |
| Selected real PostgreSQL suite, including hard JVM exits | 36 tests passed; 0 failures/errors/skips |
| Final timeout/configuration hardening smoke | Unit suite passed again; 2 affected PostgreSQL tests passed |
| Corrected snapshot benchmark | Passed at 58.67 seconds in final measured run |
| Corrected 200,000-event benchmark | Passed; actual metrics in BENCHMARK_RESULTS.md |
| Corrected 20-thread / 20,000-request benchmark | Passed; all 100 employees ended at maximum version |
| RabbitDeliveryIT, RedisCacheIT, InfrastructureFailureIT, ProcessCrashIT container attempt | 4 class-setup errors: no valid Docker environment; business methods did not execute |
| Docker Compose CLI/startup | Could not execute: docker command not found |
| Compose/application YAML structural parse | Passed; app, PostgreSQL, Redis and RabbitMQ services present (not a Docker schema/runtime check) |

**The full default container verification is not green.** The 71 passing functional tests and three completed benchmark cases do not prove live RabbitMQ/Redis/network/Compose behavior. Default Testcontainers execution fails on this host; there is no automatic fallback or Docker-based success claim.

Safe archived suite names/case names/totals: [unit summary](test-results/unit-full.json), [PostgreSQL summary](test-results/postgresql-full.json). Full local Surefire/Failsafe reports are in `.tools/build-native/`; targeted smoke reruns can overwrite individual XML files, so the full-run summaries were captured beforehand. Infrastructure setup reports are in `.tools/build-container-check/failsafe-reports/`. Raw measured benchmark JSON is in [benchmarks](benchmarks).

## PostgreSQL coverage actually executed

- CREATE, UPDATE and DEACTIVATE; per-record validation; mixed invalid batches; phone/office/version/mass-assignment rejection.
- Versions 5,7,6; replay with no employee/receipt/version/outbox changes; canonical equal-version duplicate/conflict and reused event ID conflict.
- 20 concurrent submissions of the same event with one logical update; versions 8 and 9 finishing at 9.
- Concurrent HR/transport ownership with no lost fields or HR source-version mutation by transport.
- Tenant read/write isolation, employee self access, admin/operator/HR service permissions and API role checks.
- Snapshot normal diff, blocked 40% missing, explicit override, empty/truncated/invalid/duplicate inputs, policy/roster review invalidation, exact-run/checksum approval, failed apply rollback and retry.
- Per-employee CSV versions with a high export sequence unable to poison lower employee counters; invalid duplicate IDs retained for detection; equal-version conflicts counted as invalid; safe paginated row-error reports.
- Java/PostgreSQL canonical hash equality.
- Multiple/no/already-cancelled bookings, allocation rollback, duplicate/redelivery behavior, committed deactivation despite failed downstream cancellation, and later-rehire booking protection.
- Durable outbox state, backoff/dead handling, fenced leases, simulated crash after broker acceptance and stable duplicate IDs (broker adapter faked only in these PostgreSQL state-machine tests).
- Real child JVM halted after committed employee/outbox transaction, leaving a pending outbox row. Real child JVM halted after first committed staging batch; lease expiry/restart recovery restaged the durable file without applying employees.

Separate container tests exist for confirmed real RabbitMQ publication, Rabbit duplicate delivery, Redis coherence/invalidation, Toxiproxy database/broker failures and process-crash-to-real-broker recovery. Their setup failed here before assertions; they remain unverified.

## Commands

The explicit PostgreSQL run (standard installed Maven):

```shell
mvn -Droster.it.jdbcUrl=jdbc:postgresql://127.0.0.1:55432/postgres -Droster.it.username=postgres -Dit.test=EventIngestionIT,ReconciliationIT,BookingCancellationIT,SecurityIT,OutboxIT,ProcessStagingCrashIT verify
```

Local execution additionally set JAVA_HOME to `.tools/jdk-21.0.13.0.2+1`, used `.tools/apache-maven-3.9.9/bin/mvn.cmd`, `-Dmaven.repo.local=.tools/m2`, and `-Droster.build.directory=.tools/build-native` to avoid stale/locked Windows build output. Default target remains `target/`.

Infrastructure attempt:

```shell
mvn -Dit.test=RabbitDeliveryIT,RedisCacheIT,InfrastructureFailureIT,ProcessCrashIT test-compile failsafe:integration-test failsafe:verify
```

After Docker is available, run `mvn verify` without native JDBC overrides, then `docker compose up --build`. The CI workflow runs this default functional suite on Java 21 and saves reports even on failure. Benchmark commands and measurement definitions are in [BENCHMARK_RESULTS.md](../BENCHMARK_RESULTS.md).

## Audit evidence and interpretation

The final real-PostgreSQL application log scan inspected **286 structured JSON records**, found **0 fixture PII matches** (fixture names/email/phone/invalid values) and **0 missing HR-event correlation IDs**. This is a finite test-log inspection, not proof about every future input. A unit test also captures event log fields and verifies safe correlation/PII behavior. SQL bind logging is disabled, DTO strings are redacted, and exception paths use a fixed ProblemDetail instance instead of echoing request paths.

Earlier iterations exposed/fixed booking clock-cutoff failures, per-row snapshot version handling, invalid-row reporting/duplicate retention, benchmark mock-invocation retention, and a failed 70.89-second snapshot baseline. Initial sandboxed test attempts could not attach the Mockito JVM agent; approved runs outside the sandbox produced the recorded results. A previous Windows/OneDrive clean deletion failure was avoided with fresh build directories. No test exclusions were added to hide infrastructure failures.

Snapshots and source benchmarks were measured on native PostgreSQL, with Redis/Rabbit publisher/consumers disabled. Snapshot heap figures are sampled JVM heap only, excluding PostgreSQL memory. JDBC counts are execute/batch-call counts, not server round trips. See the benchmark report for all limitations and the preserved failed baseline.

The final executable Spring Boot JAR was inspected (77,742,234 bytes) and contains all nine Flyway migrations. The temporary native PostgreSQL server was stopped after the tests; test data and local tooling remain under ignored `.tools/` for inspection/reuse. It is not an installed Windows service.
