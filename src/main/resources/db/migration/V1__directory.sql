CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL CHECK (length(trim(name)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE offices (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    code VARCHAR(40) NOT NULL CHECK (length(trim(code)) > 0),
    name VARCHAR(160) NOT NULL CHECK (length(trim(name)) > 0),
    active BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, code)
);
CREATE TABLE employees (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    external_employee_id VARCHAR(80) NOT NULL CHECK (length(trim(external_employee_id)) > 0),
    name VARCHAR(160) NOT NULL CHECK (length(trim(name)) > 0),
    email VARCHAR(254) NOT NULL CHECK (length(trim(email)) > 0),
    phone VARCHAR(20),
    manager_id UUID,
    office_code VARCHAR(40) NOT NULL,
    pickup_point VARCHAR(300),
    special_assistance BOOLEAN NOT NULL DEFAULT false,
    source_version BIGINT NOT NULL DEFAULT 0 CHECK (source_version >= 0),
    source_event_id UUID,
    active BOOLEAN NOT NULL DEFAULT true,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT uq_employee_external UNIQUE (tenant_id, external_employee_id),
    CONSTRAINT fk_employee_office FOREIGN KEY (tenant_id, office_code) REFERENCES offices(tenant_id, code),
    CONSTRAINT fk_employee_manager FOREIGN KEY (tenant_id, manager_id) REFERENCES employees(tenant_id, id),
    CONSTRAINT ck_employee_self_manager CHECK (manager_id IS NULL OR manager_id <> id)
);
CREATE INDEX ix_employee_active ON employees(tenant_id, active, id);
CREATE INDEX ix_employee_manager ON employees(tenant_id, manager_id);
CREATE INDEX ix_employee_office ON employees(tenant_id, office_code);
