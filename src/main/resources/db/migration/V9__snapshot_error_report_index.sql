CREATE INDEX ix_staging_errors ON snapshot_staging(tenant_id,run_id,row_number) WHERE NOT valid;
