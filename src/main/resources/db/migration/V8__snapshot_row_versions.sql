ALTER TABLE snapshot_staging ADD COLUMN source_version BIGINT CHECK(source_version>=0);
UPDATE snapshot_staging s SET source_version=r.source_version FROM reconciliation_runs r
WHERE s.tenant_id=r.tenant_id AND s.run_id=r.id AND s.valid;
