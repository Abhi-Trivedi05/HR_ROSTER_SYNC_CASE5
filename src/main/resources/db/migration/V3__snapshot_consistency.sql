ALTER TABLE tenants ADD COLUMN hr_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE reconciliation_runs ADD COLUMN roster_revision BIGINT;
ALTER TABLE reconciliation_runs ADD COLUMN recovery_attempts INTEGER NOT NULL DEFAULT 0;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE FUNCTION roster_canonical_field(value TEXT) RETURNS BYTEA LANGUAGE SQL IMMUTABLE PARALLEL SAFE AS $$
 SELECT CASE WHEN value IS NULL THEN int4send(-1) ELSE int4send(octet_length(convert_to(value,'UTF8'))) || convert_to(value,'UTF8') END
$$;
CREATE FUNCTION roster_hr_hash(n TEXT,e TEXT,p TEXT,m UUID,o TEXT,a BOOLEAN) RETURNS TEXT LANGUAGE SQL IMMUTABLE PARALLEL SAFE AS $$
 SELECT encode(digest(roster_canonical_field(n)||roster_canonical_field(e)||roster_canonical_field(p)||
   roster_canonical_field(m::text)||roster_canonical_field(o)||roster_canonical_field(a::text),'sha256'),'hex')
$$;
