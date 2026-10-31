ALTER TABLE tenants ADD COLUMN missing_threshold NUMERIC(6,5) CHECK (missing_threshold > 0 AND missing_threshold <= 1);
ALTER TABLE tenants ADD COLUMN config_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE employees ADD COLUMN hr_hash CHAR(64);
ALTER TABLE employees ADD COLUMN transport_revision BIGINT NOT NULL DEFAULT 0 CHECK (transport_revision >= 0);
ALTER TABLE employees ADD COLUMN deactivated_at TIMESTAMPTZ;

CREATE TABLE processed_events (
 tenant_id UUID NOT NULL REFERENCES tenants(id), external_employee_id VARCHAR(80) NOT NULL,
 source_version BIGINT NOT NULL CHECK (source_version >= 0), payload_hash CHAR(64) NOT NULL,
 outcome VARCHAR(24) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY (tenant_id, external_employee_id, source_version)
);
CREATE TABLE event_receipts (
 tenant_id UUID NOT NULL, event_id UUID NOT NULL, external_employee_id VARCHAR(80) NOT NULL,
 source_version BIGINT NOT NULL, payload_hash CHAR(64) NOT NULL,
 PRIMARY KEY (tenant_id, event_id),
 FOREIGN KEY (tenant_id, external_employee_id, source_version)
 REFERENCES processed_events(tenant_id, external_employee_id, source_version)
);
CREATE TABLE reconciliation_runs (
 tenant_id UUID NOT NULL REFERENCES tenants(id), id UUID NOT NULL,
 status VARCHAR(24) NOT NULL CHECK (status IN ('STAGING','FAILED','READY','BLOCKED','INVALID','APPROVED','COMPLETED')),
 checksum CHAR(64), expected_checksum CHAR(64) NOT NULL,
 source_version BIGINT NOT NULL CHECK (source_version >= 0), source_cutoff TIMESTAMPTZ NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0, expected_count BIGINT NOT NULL DEFAULT 0,
 incoming_count BIGINT NOT NULL DEFAULT 0, missing_count BIGINT NOT NULL DEFAULT 0,
 invalid_count BIGINT NOT NULL DEFAULT 0, duplicate_count BIGINT NOT NULL DEFAULT 0,
 new_count BIGINT NOT NULL DEFAULT 0, updated_count BIGINT NOT NULL DEFAULT 0,
 unchanged_count BIGINT NOT NULL DEFAULT 0, missing_fraction NUMERIC(12,10) NOT NULL DEFAULT 0,
 threshold NUMERIC(6,5), config_revision BIGINT, error_code VARCHAR(64),
 approved_checksum CHAR(64), approved_by VARCHAR(160), approved_at TIMESTAMPTZ,
 override_brake BOOLEAN NOT NULL DEFAULT false, staging_token UUID, lease_until TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY (tenant_id,id)
);
CREATE TABLE snapshot_staging (
 tenant_id UUID NOT NULL, run_id UUID NOT NULL, row_number BIGINT NOT NULL,
 external_employee_id VARCHAR(80), name VARCHAR(160), email VARCHAR(254), phone VARCHAR(20),
 manager_id UUID, office_code VARCHAR(40), active BOOLEAN, hr_hash CHAR(64), bucket SMALLINT,
 valid BOOLEAN NOT NULL, error_code VARCHAR(64),
 PRIMARY KEY (tenant_id,run_id,row_number),
 FOREIGN KEY (tenant_id,run_id) REFERENCES reconciliation_runs(tenant_id,id) ON DELETE CASCADE
);
CREATE INDEX ix_staging_identity ON snapshot_staging(tenant_id,run_id,external_employee_id);
CREATE INDEX ix_staging_bucket ON snapshot_staging(tenant_id,run_id,bucket,row_number) WHERE valid;
CREATE TABLE reconciliation_audit (
 tenant_id UUID NOT NULL, run_id UUID NOT NULL, employee_id UUID NOT NULL,
 action VARCHAR(24) NOT NULL, source_version BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY (tenant_id,run_id,employee_id),
 FOREIGN KEY (tenant_id,run_id) REFERENCES reconciliation_runs(tenant_id,id)
);
CREATE TABLE outbox (
 tenant_id UUID NOT NULL REFERENCES tenants(id), id UUID NOT NULL, employee_id UUID NOT NULL,
 event_type VARCHAR(40) NOT NULL, source_version BIGINT NOT NULL, effective_at TIMESTAMPTZ NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','CLAIMED','PUBLISHED','DEAD')),
 attempts INTEGER NOT NULL DEFAULT 0, available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 lease_until TIMESTAMPTZ, claim_token UUID, last_error VARCHAR(64), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY (tenant_id,id)
);
CREATE INDEX ix_outbox_pending ON outbox(tenant_id,status,available_at,created_at);
CREATE TABLE message_inbox (
 tenant_id UUID NOT NULL, consumer VARCHAR(64) NOT NULL, event_id UUID NOT NULL,
 payload_hash CHAR(64) NOT NULL, processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY (tenant_id,consumer,event_id)
);
CREATE TABLE booking_cabs (
 tenant_id UUID NOT NULL REFERENCES tenants(id), id UUID NOT NULL, capacity INTEGER NOT NULL CHECK (capacity > 0),
 allocated_seats INTEGER NOT NULL DEFAULT 0 CHECK (allocated_seats >= 0 AND allocated_seats <= capacity),
 PRIMARY KEY (tenant_id,id)
);
CREATE TABLE bookings (
 tenant_id UUID NOT NULL, id UUID NOT NULL, employee_id UUID NOT NULL, cab_id UUID NOT NULL,
 starts_at TIMESTAMPTZ NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED'
 CHECK (status IN ('CONFIRMED','CANCELLED','COMPLETED')), cancelled_at TIMESTAMPTZ,
 cancellation_event_id UUID, PRIMARY KEY (tenant_id,id),
 FOREIGN KEY (tenant_id,employee_id) REFERENCES employees(tenant_id,id),
 FOREIGN KEY (tenant_id,cab_id) REFERENCES booking_cabs(tenant_id,id)
);
CREATE INDEX ix_booking_future ON bookings(tenant_id,employee_id,starts_at,id) WHERE status='CONFIRMED';
CREATE TABLE seat_allocations (
 tenant_id UUID NOT NULL, booking_id UUID NOT NULL, cab_id UUID NOT NULL,
 seats INTEGER NOT NULL CHECK (seats > 0), released BOOLEAN NOT NULL DEFAULT false,
 PRIMARY KEY (tenant_id,booking_id),
 FOREIGN KEY (tenant_id,booking_id) REFERENCES bookings(tenant_id,id),
 FOREIGN KEY (tenant_id,cab_id) REFERENCES booking_cabs(tenant_id,id)
);
CREATE TABLE app_users (
 tenant_id UUID NOT NULL REFERENCES tenants(id), id UUID NOT NULL, username VARCHAR(80) NOT NULL,
 password_hash VARCHAR(100) NOT NULL, role VARCHAR(32) NOT NULL
 CHECK (role IN ('EMPLOYEE','ADMIN','OPERATOR','HR_INTEGRATION')),
 employee_id UUID, PRIMARY KEY (tenant_id,id), UNIQUE (tenant_id,username),
 FOREIGN KEY (tenant_id,employee_id) REFERENCES employees(tenant_id,id)
);
