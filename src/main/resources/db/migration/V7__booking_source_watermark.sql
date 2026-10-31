ALTER TABLE bookings ADD COLUMN source_version_at_booking BIGINT NOT NULL DEFAULT 0 CHECK(source_version_at_booking>=0);
UPDATE bookings b SET source_version_at_booking=e.source_version FROM employees e
WHERE b.tenant_id=e.tenant_id AND b.employee_id=e.id;
