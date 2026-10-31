# API contracts and examples

All business calls require `Authorization: Bearer TOKEN`. Tenant identity comes exclusively from the verified token. Request bodies never select a business tenant. Use `/api/v1/auth/token` with a tenant-qualified local demo username to obtain a token; production uses the configured identity provider.

| Method/path | Role | Contract |
| --- | --- | --- |
| POST `/api/v1/hr/events` | HR_INTEGRATION | One event; one EventResult |
| POST `/api/v1/hr/events/batch` | HR_INTEGRATION | JSON array, 1..1000 records; result for each |
| GET `/api/v1/employees/{internalId}` | EMPLOYEE (own), ADMIN, OPERATOR | Profile DTO including global revision and transportRevision |
| GET `/api/v1/employees?page=0&size=50` | ADMIN, OPERATOR | Bounded slice, size 1..200 |
| PATCH `/api/v1/employees/{internalId}/transport` | ADMIN | Complete transport-preference pair and expected transport revision |
| GET `/api/v1/offices/{code}` | ADMIN, OPERATOR, HR_INTEGRATION | Cached metadata with authoritative fingerprint |
| PATCH `/api/v1/offices/{code}` | OPERATOR | name and active; invalidates dependent snapshot review |
| PATCH `/api/v1/tenant/config` | OPERATOR | missingThreshold in (0,1], <=5 decimal places |
| POST `/api/v1/reconciliations` | OPERATOR | Create exact-checksum run |
| PUT `/api/v1/reconciliations/{runId}/csv` | OPERATOR | Raw UTF-8 text/csv stream |
| GET `/api/v1/reconciliations/{runId}` | OPERATOR | Safe counts/status/approval report |
| GET `/api/v1/reconciliations/{runId}/errors?afterRow=0&limit=100` | OPERATOR | Bounded row numbers and error codes only |
| POST `/api/v1/reconciliations/{runId}/refresh` | OPERATOR | Recompute review after roster/policy changes |
| POST `/api/v1/reconciliations/{runId}/approve` | OPERATOR | checksum, expectedRevision, explicit overrideSafetyBrake |
| POST `/api/v1/reconciliations/{runId}/apply` | OPERATOR | Apply persisted approval atomically |
| POST `/api/v1/outbox/{eventId}/requeue` | OPERATOR | Requeue a DEAD outbox record without changing event ID |
| POST `/api/v1/bookings` | EMPLOYEE | Reserve own cab seats; authoritative active/capacity checks |
| GET `/actuator/prometheus` | OPERATOR | Prometheus scrape |
| GET `/actuator/health` | public | Health without details |

## HR events

```shell
curl -X POST http://localhost:8080/api/v1/hr/events \
  -H "Authorization: Bearer $HR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"eventId":"33333333-3333-4333-8333-333333333333","employeeId":"HR-1001","version":5,"eventType":"CREATE","timestamp":"2026-01-01T00:00:00Z","data":{"name":"Example","email":"example@example.invalid","phone":"+919876543210","managerId":null,"officeCode":"BLR","active":true}}'
```

CREATE/UPDATE are full HR images, not patches. UPDATE version 7 applies; later version 6 is STALE. Replay the exact original event ID/content to get DUPLICATE with no business-row writes. Same version with conflicting canonical content returns VERSION_CONFLICT; reused event ID with conflicting content returns EVENT_ID_CONFLICT. Timestamps are ISO-8601 audit metadata; source version determines order.

DEACTIVATE body:

```json
{"eventId":"44444444-4444-4444-8444-444444444444","employeeId":"HR-1001","version":8,"eventType":"DEACTIVATE","timestamp":"2026-01-01T01:00:00Z","data":null}
```

POST `/batch` accepts an array of the same objects. Individual malformed types, fractional/string/negative versions, missing identifiers, invalid phone/email, unknown office and unknown properties produce independent results. Syntactically invalid JSON or a non-array batch cannot be split into meaningful records and receives HTTP 400. JSON requests are capped at 2 MiB (known oversized length receives 413; chunked over-limit input receives safe 400 when decoding aborts).

```json
[
  {"index":0,"eventId":"33333333-3333-4333-8333-333333333333","status":"APPLIED","errorCode":"NONE"},
  {"index":1,"eventId":null,"status":"REJECTED","errorCode":"INVALID_FIELDS"}
]
```

HTTP 200 carries per-record APPLIED, DUPLICATE, STALE, REJECTED or RETRYABLE. A RETRYABLE result did not commit; resend with the same event ID. Unknown fields, including pickupPoint, specialAssistance or tenantId, are forbidden. Error codes never contain rejected values.

## Transport update

```shell
curl -X PATCH http://localhost:8080/api/v1/employees/INTERNAL_UUID/transport \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"pickupPoint":"Office gate","specialAssistance":true,"expectedRevision":0}'
```

`expectedRevision` means the profile's **transportRevision**, not global revision or sourceVersion. The response contains the next transportRevision. Omitted pickupPoint clears it; specialAssistance and expectedRevision are required. HR fields/active/sourceVersion cannot be supplied. A competing admin write produces 409; a concurrent HR update does not change transportRevision.

## Snapshot workflow

CSV header and example:

```csv
employeeId,version,name,email,phone,managerId,officeCode,active
HR-1001,9,Example,example@example.invalid,+919876543210,,BLR,true
```

Compute SHA-256 of the exact file bytes, including header and line endings. Each row has its own nonnegative integer employee version. The run's sourceVersion is export-sequence metadata and does not set employee versions. sourceCutoff is the export's stable cutoff instant and cannot be in the future. A snapshot must contain at least one valid employee; empty files cannot intentionally deactivate the full tenant.

```shell
curl -X POST http://localhost:8080/api/v1/reconciliations \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"checksum":"EXACT_64_CHARACTER_LOWERCASE_SHA256","sourceVersion":9,"sourceCutoff":"2026-01-01T02:00:00Z"}'
curl -X PUT http://localhost:8080/api/v1/reconciliations/RUN_UUID/csv \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H 'Content-Type: text/csv' --data-binary @snapshot.csv
curl http://localhost:8080/api/v1/reconciliations/RUN_UUID -H "Authorization: Bearer $OPERATOR_TOKEN"
```

Inspect status, expectedCount, incomingCount, missingCount, invalidCount, duplicateCount, newCount, updatedCount, unchangedCount, missingFraction and threshold. No rows have been applied at this point.

```shell
curl -X POST http://localhost:8080/api/v1/reconciliations/RUN_UUID/approve \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"checksum":"EXACT_64_CHARACTER_LOWERCASE_SHA256","expectedRevision":1,"overrideSafetyBrake":false}'
curl -X POST http://localhost:8080/api/v1/reconciliations/RUN_UUID/apply \
  -H "Authorization: Bearer $OPERATOR_TOKEN"
```

Use the current report revision, not the illustrative 1. A BLOCKED run needs `overrideSafetyBrake:true` after operator investigation. INVALID runs cannot be overridden. If errorCode is REVIEW_REQUIRED, inspect the refreshed report and explicitly approve again; no employee changes occurred. Never copy an approval to a different run/checksum.

```shell
curl -X PATCH http://localhost:8080/api/v1/tenant/config \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"missingThreshold":0.30}'
```

## Booking sample

The demo profile already provisions this four-seat cab. For a separately provisioned tenant, create a cab through a controlled database session, then an employee token may reserve its own seats:

```sql
INSERT INTO booking_cabs(tenant_id,id,capacity)
VALUES ('11111111-1111-4111-8111-111111111111','55555555-5555-4555-8555-555555555555',4)
ON CONFLICT DO NOTHING;
```

```json
{"cabId":"55555555-5555-4555-8555-555555555555","startsAt":"2027-01-01T08:00:00Z","seats":1}
```

POST to `/api/v1/bookings` with EMPLOYEE token; no employee or tenant selector is accepted. Inactive employees and insufficient capacity are rejected. After an HR exit, outbox delivery cancels future bookings and releases seats asynchronously.

## Errors

Unauthenticated/invalid token: 401. Authenticated insufficient role/self/tenant permission: 403. Scoped missing resource: 404. Bad DTO/schema/input: 400. Stale transport revision or conflicting run state: 409. Database availability failure: 503 where translated. Responses contain errorCode, safe message, correlationId and timestamp; MVC errors also include standard ProblemDetail fields. `X-Correlation-Id` is generated by the service, never copied from untrusted request text. Stack traces and employee values are not returned.
