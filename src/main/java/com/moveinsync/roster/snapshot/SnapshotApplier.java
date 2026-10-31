package com.moveinsync.roster.snapshot;

import com.moveinsync.roster.shared.FailureProbe;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Set-based, one atomic approved-run transaction. No 200K-row Java collection. */
@Component
public class SnapshotApplier {
    private final JdbcTemplate jdbc;
    private final FailureProbe failures;
    public SnapshotApplier(JdbcTemplate jdbc,FailureProbe failures) { this.jdbc=jdbc;this.failures=failures; }
    public void apply(UUID tenant,UUID run,SnapshotModels.Report report) {
        jdbc.execute("create temporary table snapshot_changes(tenant_id uuid,id uuid,active boolean,source_version bigint,action varchar(24)) on commit drop");
        jdbc.update("""
            with changed as (
              insert into employees(id,tenant_id,external_employee_id,name,email,phone,manager_id,office_code,active,
                source_version,source_event_id,hr_hash,created_at,updated_at,deactivated_at)
              select coalesce(e.id,gen_random_uuid()),s.tenant_id,s.external_employee_id,s.name,s.email,s.phone,s.manager_id,s.office_code,s.active,
                s.source_version,md5(?::text||':'||s.external_employee_id)::uuid,s.hr_hash,now(),now(),case when s.active then null else now() end
              from snapshot_staging s left join employees e on e.tenant_id=s.tenant_id and e.external_employee_id=s.external_employee_id
              where s.tenant_id=? and s.run_id=? and s.valid and (e.id is null or e.source_version<s.source_version)
              on conflict(tenant_id,external_employee_id) do update set name=excluded.name,email=excluded.email,phone=excluded.phone,
                manager_id=excluded.manager_id,office_code=excluded.office_code,active=excluded.active,source_version=excluded.source_version,
                source_event_id=excluded.source_event_id,hr_hash=excluded.hr_hash,updated_at=now(),hr_updated_at=now(),deactivated_at=excluded.deactivated_at,revision=employees.revision+1
              where employees.source_version<excluded.source_version
              returning tenant_id,id,active,source_version
            ) insert into snapshot_changes select tenant_id,id,active,source_version,'UPSERT' from changed where tenant_id=?
            """,run,tenant,run,tenant);
        jdbc.update("""
            with changed as (
             update employees e set active=false,
               hr_hash=roster_hr_hash(e.name,e.email,e.phone,e.manager_id,e.office_code,false),revision=e.revision+1,updated_at=now(),hr_updated_at=now(),deactivated_at=now()
             where e.tenant_id=? and e.active and e.hr_updated_at<=? and not exists
              (select 1 from snapshot_staging s where s.tenant_id=e.tenant_id and s.run_id=? and s.valid and s.external_employee_id=e.external_employee_id)
             returning e.tenant_id,e.id,e.active,e.source_version
            ) insert into snapshot_changes select tenant_id,id,active,source_version,'MISSING' from changed where tenant_id=?
            """,tenant,Timestamp.from(report.sourceCutoff()),run,tenant);
        failures.at("snapshot-employees-written");
        jdbc.update("insert into reconciliation_audit(tenant_id,run_id,employee_id,action,source_version) select tenant_id,?,id,action,source_version from snapshot_changes where tenant_id=?",run,tenant);
        jdbc.update("insert into outbox(tenant_id,id,employee_id,event_type,source_version,effective_at) select tenant_id,gen_random_uuid(),id,case when active then 'EMPLOYEE_UPDATED' else 'EMPLOYEE_DEACTIVATED' end,source_version,now() from snapshot_changes where tenant_id=?",tenant);
        jdbc.update("update tenants set hr_revision=hr_revision+1 where id=? and exists(select 1 from snapshot_changes where tenant_id=?)",tenant,tenant);
        jdbc.update("update reconciliation_runs set status='COMPLETED',revision=revision+1,updated_at=now() where tenant_id=? and id=? and status='APPROVED' and approved_checksum=checksum",tenant,run);
        failures.at("snapshot-before-commit");
    }
}
