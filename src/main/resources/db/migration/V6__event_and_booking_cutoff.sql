ALTER TABLE processed_events ADD COLUMN source_timestamp TIMESTAMPTZ;
ALTER TABLE bookings ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
