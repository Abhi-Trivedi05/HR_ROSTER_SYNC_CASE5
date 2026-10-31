"""Exercise the running local demo stack; do not run against a production database.

Uses only Python's standard library. Credentials/tokens and response payloads are never printed.
"""
import csv
from datetime import datetime, timedelta, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent
BASE = os.environ.get('SMOKE_BASE_URL', 'http://localhost:8080').rstrip('/')
TENANT = '11111111-1111-4111-8111-111111111111'
DEMO_EMPLOYEE = '22222222-2222-4222-8222-222222222222'
CAB = '55555555-5555-4555-8555-555555555555'


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def api(method, path, token=None, data=None, raw=None):
    headers = {}
    if token:
        headers['Authorization'] = f'Bearer {token}'
    if raw is not None:
        body, headers['Content-Type'] = raw, 'text/csv'
    elif data is not None:
        body, headers['Content-Type'] = json.dumps(data).encode(), 'application/json'
    else:
        body = None
    request = urllib.request.Request(BASE + path, data=body, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = response.read()
        return json.loads(payload) if payload else None


def login(role):
    return api('POST', '/api/v1/auth/token', data={
        'username': f'{TENANT}:{role}',
        'password': os.environ.get('DEMO_PASSWORD', 'LocalDemoOnly!2026'),
    })['accessToken']


def compose(*arguments):
    return subprocess.run(['docker', 'compose', *arguments], cwd=ROOT, check=True,
                          capture_output=True, text=True, timeout=30).stdout.strip()


def database(query):
    return compose('exec', '-T', 'postgres', 'psql', '-U', 'roster', '-d', 'roster',
                   '-v', 'ON_ERROR_STOP=1', '-A', '-t', '-c', query)


def event(token, employee, version, kind, data=None):
    result = api('POST', '/api/v1/hr/events', token, {
        'eventId': str(uuid.uuid4()), 'employeeId': employee, 'version': version,
        'eventType': kind, 'timestamp': datetime.now(timezone.utc).isoformat(), 'data': data,
    })
    require(result['status'] == 'APPLIED', f'{kind} event was not applied')


def profile(token, employee):
    return api('GET', f'/api/v1/employees/{employee}', token)


def wait_for(check, message, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(1)
    raise AssertionError(message)


def main():
    require(api('GET', '/actuator/health')['status'] == 'UP', 'Stack health is not UP')
    hr, admin, operator, employee = (login(role) for role in ('hr', 'admin', 'operator', 'employee'))
    print('PASS: health and JWT authentication for all four demo roles')

    external = 'SMOKE-' + uuid.uuid4().hex[:12]
    data = {'name': 'Compose fixture', 'email': 'compose@example.invalid', 'phone': None,
            'managerId': None, 'officeCode': 'BLR', 'active': True}
    event(hr, external, 1, 'CREATE', data)
    page = 0
    while True:
        records = api('GET', f'/api/v1/employees?page={page}&size=200', admin)
        found = next((p for p in records['items'] if p['externalEmployeeId'] == external), None)
        if found:
            break
        require(records['hasNext'], 'Created employee not visible through the tenant API')
        page += 1
    internal = str(uuid.UUID(found['id']))
    api('PATCH', f'/api/v1/employees/{internal}/transport', admin,
        {'pickupPoint': 'Demo gate', 'specialAssistance': True,
         'expectedRevision': found['transportRevision']})
    data['name'] = 'Updated compose fixture'
    event(hr, external, 2, 'UPDATE', data)
    current = profile(admin, internal)
    require(current['sourceVersion'] == 2 and current['pickupPoint'] == 'Demo gate'
            and current['specialAssistance'], 'HR/transport ownership or cache coherence failed')
    profile(admin, internal)
    key = f"roster:{TENANT}:employee:{internal}:{current['revision']}"
    require(compose('exec', '-T', 'redis', 'redis-cli', '--no-auth-warning', 'EXISTS', key) == '1',
            'Profile cache was not populated in Redis')
    print('PASS: HR ingestion, transport ownership and authenticated Redis caching')

    # Check non-root snapshot-volume writes and real approval/application, not only API health.
    profiles = [current, profile(admin, DEMO_EMPLOYEE)]
    stream = io.StringIO(newline='')
    writer = csv.writer(stream)
    writer.writerow(['employeeId', 'version', 'name', 'email', 'phone', 'managerId', 'officeCode', 'active'])
    for p in profiles:
        writer.writerow([p['externalEmployeeId'], p['sourceVersion'] + 1, p['name'], p['email'],
                         p['phone'] or '', p['managerId'] or '', p['officeCode'],
                         'true' if p['active'] else 'false'])
    content = stream.getvalue().encode('utf-8')
    checksum = hashlib.sha256(content).hexdigest()
    run = api('POST', '/api/v1/reconciliations', operator, {
        'checksum': checksum, 'sourceVersion': 1,
        'sourceCutoff': datetime.now(timezone.utc).isoformat(),
    })
    run_path = f"/api/v1/reconciliations/{run['runId']}"
    report = api('PUT', run_path + '/csv', operator, raw=content)
    require(report['status'] == 'READY', 'Compose snapshot did not become READY')
    approval = api('POST', run_path + '/approve', operator, {
        'checksum': checksum, 'expectedRevision': report['revision'], 'overrideSafetyBrake': False,
    })
    require(approval['status'] == 'APPROVED', 'Compose snapshot approval failed')
    require(api('POST', run_path + '/apply', operator)['status'] == 'COMPLETED',
            'Compose snapshot application failed')
    require(profile(admin, internal)['pickupPoint'] == 'Demo gate', 'Snapshot overwrote transport data')
    print('PASS: persistent snapshot storage, approval and atomic application')

    baseline = database(f"select allocated_seats from booking_cabs where tenant_id='{TENANT}' and id='{CAB}'")
    demo = profile(admin, DEMO_EMPLOYEE)
    booking = api('POST', '/api/v1/bookings', employee, {
        'cabId': CAB, 'seats': 1,
        'startsAt': (datetime.now(timezone.utc) + timedelta(days=1)).isoformat(),
    })
    booking_id = str(uuid.UUID(booking['bookingId']))
    exit_version = demo['sourceVersion'] + 1
    try:
        event(hr, 'DEMO-EMPLOYEE', exit_version, 'DEACTIVATE')

        def cancellation_committed():
            result = database(f"""select b.status || '|' || a.released::text || '|' || c.allocated_seats
                from bookings b join seat_allocations a on a.tenant_id=b.tenant_id and a.booking_id=b.id
                join booking_cabs c on c.tenant_id=b.tenant_id and c.id=b.cab_id
                where b.tenant_id='{TENANT}' and b.id='{booking_id}'""")
            return result == f'CANCELLED|true|{baseline}'

        wait_for(cancellation_committed, 'RabbitMQ cancellation/seat release did not complete')
        require(not profile(admin, DEMO_EMPLOYEE)['active'], 'Employee deactivation was not committed')
        print('PASS: PostgreSQL outbox -> RabbitMQ -> inbox -> cancellation and seat release')
    finally:
        # Leave the demo employee usable after the destructive lifecycle scenario.
        restored = {field: demo[field] for field in ('name', 'email', 'phone', 'managerId', 'officeCode')}
        restored['active'] = True
        event(hr, 'DEMO-EMPLOYEE', exit_version + 1, 'UPDATE', restored)

    wait_for(lambda: database(f"select count(*) from outbox where tenant_id='{TENANT}' and status<>'PUBLISHED'") == '0',
             'Outbox did not drain after the scenario')
    print('PASS: full Compose smoke test')


if __name__ == '__main__':
    main()
