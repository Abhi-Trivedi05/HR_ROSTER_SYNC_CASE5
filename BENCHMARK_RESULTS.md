# Actual benchmark results

Execution date: 2026-10-31, Asia/Calcutta. These are executed results, not estimates. Raw UTC timestamps and complete measurements are in [docs/benchmarks](docs/benchmarks).

Hardware: AMD Ryzen 5 5600H, 6 physical cores / 12 logical processors, 7,883,304,960 bytes usable physical RAM. Windows 11; Microsoft OpenJDK 21.0.13.0.2; application/test JVM capped at 512 MiB. PostgreSQL 16.10 ran natively on loopback on the same laptop with default fsync enabled and 128 MiB shared_buffers. Data files were under the workspace on the local Windows/OneDrive filesystem. This was not an isolated production load lab.

| Workload | Records/requests | Seconds | Rows or requests/s | p50 ms | p95 ms | p99 ms | Sampled peak heap MiB | JDBC execute/batch calls |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| snapshot-200k | 200,000 | 58.67 | 3408.91 | 419.130 | 552.523 | 1665.088 | 193.35 | 582 |
| ingestion-200k | 200,000 | 676.41 | 295.68 | 3.176 | 4.642 | 5.880 | 86.57 | 2,200,000 |
| concurrent-updates-20-threads | 20,000 | 57.13 | 350.08 | 53.708 | 72.024 | 105.433 | 86.63 | 219,400 |

Final snapshot target (<60 seconds): **met in this executed run**. The final measurement was 58.67 seconds. Staging/diff took 19.65 seconds and approved atomic apply took 38.74 seconds. This narrow observed margin is not a production SLA or a guarantee on different hardware/data widths.

## What was measured

- Snapshot: 200,000 NEW employees, one office, short fixture name/email, null phone/manager, active=true, per-row version 1. The CSV is generated to disk as a stream. Timing includes run creation, file copy/checksum, 5,000-row JDBC staging, reference validation, duplicate detection, 256-bucket diff, safety validation, programmatic operator approval, atomic employee/audit/outbox insertion. Dataset generation, application startup and human review time are excluded. Heap is sampled every 10 ms; short-lived peaks between samples may be missed.
- Snapshot percentiles describe 40 parsing/staging batches of 5,000 rows, NOT repeated end-to-end reconciliation runs. The first batch sample includes upload overhead. The single full-run elapsed time is reported separately.
- Ingestion: 200,000 distinct CREATE events in one tenant, submitted serially through the JSON/validation/batch service into independent committed employee/outbox/receipt transactions. Percentiles are per-event service calls; HTTP, RabbitMQ delivery and Redis are excluded. Event log output is disabled for this measurement.
- Concurrent updates: 20 threads, 100 employees, 20,000 requests, one tenant. Percentiles are per-event core transaction latency and include stale arrivals. The benchmark verifies every employee ends at its maximum submitted version. Throughput counts requests, not only applied updates.
- Query counts are measured JDBC execute* invocations. executeBatch counts as one call; driver rewriting/protocol messages, BEGIN/COMMIT and affected row counts are not server-query counts. Do not interpret 582 JDBC operations as 582 PostgreSQL row operations or network round trips. [Counter implementation](src/test/java/com/moveinsync/roster/benchmark/QueryCountingConfig.java).
- Cache, publisher and consumers are disabled so the source database path is measured. A constant-memory benchmark probe replaces Mockito invocation recording in measured paths. Latency samples are retained by the measurement harness, not by production ingestion.

## Earlier runs and corrections

The initial 1,000-row-batch prototype snapshot took **70.89 seconds**, failing the target. Its sampled heap was 84.70 MiB and it recorded 1,538 JDBC execute/batch calls. It preceded the per-row-version/report refinements and benchmark-probe correction; it is preserved as [baseline raw data](docs/benchmarks/snapshot-200k-baseline.json), not a final correctness/performance claim.

The first corrected 5,000-row-batch run took **51.95 seconds**, with 141.0 MiB sampled heap and 581 JDBC execute/batch calls. The final-code rerun is the table above (including later invalid-row classification and error-report indexing). Both runs are preserved; differences include database size/cache state and normal laptop scheduling/I/O. No claim of statistically stable p99 or uniform <60-second behavior is made from these few runs.

An initial ingestion attempt was interrupted after discovering that Mockito's probe recorded every invocation, contaminating the heap measurement. No complete result from that attempt is used. All ingestion/concurrency numbers in the table come from the corrected, completed runs.

## Reproduce

With Java 21, Maven and Docker:

```shell
mvn -Pbenchmarks -Dit.test=BenchmarkIT verify
```

The benchmark uses PostgreSQL Testcontainers by default. On this Docker-less host, the same benchmark class was explicitly run against the isolated native PostgreSQL fixture:

```shell
mvn -Pbenchmarks -Droster.it.jdbcUrl=jdbc:postgresql://127.0.0.1:55432/roster_bench_optimized -Droster.it.username=postgres -Dit.test=BenchmarkIT verify
```

The snapshot and other methods were executed in separate invocations to investigate/refine the failed target; all three completed methods are recorded. Functional PostgreSQL tests used a separate database. Results are written to benchmark-output/*.json; progress.json is not a completed benchmark result. Copy approved raw result files to docs/benchmarks, then run `python tools/render_benchmark_report.py` to regenerate this document.

## Limits and remaining validation

The coarse tenant lock is visible in the measured single-tenant ingestion/update throughput and queueing latency. This implementation favors ordering/ownership/approval correctness; reducing that contention requires a carefully tested finer locking design. The snapshot's largest cost is database application, not JVM file loading.

These measurements used native PostgreSQL because Docker/WSL are unavailable on the local host. RabbitMQ/Redis/network integration and the complete Docker Compose startup subsequently passed in Docker-enabled GitHub CI; see the [verification record](docs/verification.md). That functional/container success does not remeasure these performance numbers inside containers.
