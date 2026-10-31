ALTER TABLE employees ADD COLUMN hr_updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
UPDATE employees SET hr_updated_at=updated_at;
