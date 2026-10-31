package com.moveinsync.roster.snapshot;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationDiffService {
    private final JdbcTemplate jdbc;
    private final HashBucketProcessor buckets;
    private final SafetyBrakeService safety;
    private final ReconciliationReportService reports;
    private final MeterRegistry metrics;
    public ReconciliationDiffService(JdbcTemplate jdbc,HashBucketProcessor buckets,SafetyBrakeService safety,ReconciliationReportService reports,MeterRegistry metrics) {
        this.jdbc=jdbc;this.buckets=buckets;this.safety=safety;this.reports=reports;this.metrics=metrics;
    }
    public SnapshotModels.Report refresh(UUID tenant,UUID run) {
        var report=reports.report(tenant,run);
        jdbc.update("""
          update snapshot_staging s set valid=false,error_code='REFERENCE_CHANGED' where s.tenant_id=? and s.run_id=? and s.valid and
          (not exists(select 1 from offices o where o.tenant_id=s.tenant_id and o.code=s.office_code and o.active)
           or (s.manager_id is not null and not exists(select 1 from employees e where e.tenant_id=s.tenant_id and e.id=s.manager_id and e.active)))
          """,tenant,run);
        jdbc.update("""
          update snapshot_staging s set valid=false,error_code='VERSION_CONFLICT' from employees e
          where s.tenant_id=? and s.run_id=? and s.valid and e.tenant_id=s.tenant_id and e.external_employee_id=s.external_employee_id
            and e.source_version=s.source_version and e.hr_hash is distinct from s.hr_hash
          """,tenant,run);
        long[] count=jdbc.queryForObject("select count(*) filter(where valid), count(*) filter(where not valid), count(*) filter(where error_code='DUPLICATE_EMPLOYEE_ID') from snapshot_staging where tenant_id=? and run_id=?",
                (rs,n)->new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3)},tenant,run);
        long expected=jdbc.queryForObject("select count(*) from employees where tenant_id=? and active and hr_updated_at<=?",Long.class,tenant,Timestamp.from(report.sourceCutoff()));
        long missing=jdbc.queryForObject("""
            select count(*) from employees e where e.tenant_id=? and e.active and e.hr_updated_at<=? and not exists
            (select 1 from snapshot_staging s where s.tenant_id=e.tenant_id and s.run_id=? and s.valid and s.external_employee_id=e.external_employee_id)
            """,Long.class,tenant,Timestamp.from(report.sourceCutoff()),run);
        long conflicts=jdbc.queryForObject("select count(*) from snapshot_staging where tenant_id=? and run_id=? and error_code='VERSION_CONFLICT'",Long.class,tenant,run);
        boolean cycle=count[1]==0&&conflicts==0&&managerCycle(tenant,run);
        var policy=safety.policy(tenant);
        var decision=SafetyBrakeService.decide(expected,missing,policy.threshold());
        var diff=buckets.diff(tenant,run);
        boolean invalid=count[0]==0 || count[1]>0 || conflicts>0 || cycle;
        String state=invalid?"INVALID":decision.blocked()?"BLOCKED":"READY";
        String code=conflicts>0?"VERSION_CONFLICT":count[0]==0?"EMPTY_OR_NO_VALID_ROWS":count[1]>0?"INVALID_ROWS":cycle?"INVALID_MANAGER_HIERARCHY":decision.blocked()?"MISSING_THRESHOLD_REACHED":null;
        long roster=jdbc.queryForObject("select hr_revision from tenants where id=?",Long.class,tenant);
        jdbc.update("""
            update reconciliation_runs set status=?,expected_count=?,incoming_count=?,missing_count=?,invalid_count=?,duplicate_count=?,
             new_count=?,updated_count=?,unchanged_count=?,missing_fraction=?,threshold=?,config_revision=?,roster_revision=?,error_code=?,
             approved_at=null,approved_by=null,approved_checksum=null,override_brake=false,revision=revision+1,updated_at=now()
            where tenant_id=? and id=?
            """,state,expected,count[0],missing,count[1],count[2],diff[0],diff[1],diff[2],decision.fraction(),policy.threshold(),policy.revision(),roster,code,tenant,run);
        metrics.summary("reconciliation.missing.fraction").record(decision.fraction().doubleValue());
        if (state.equals("BLOCKED")) metrics.counter("reconciliation.blocked").increment();
        return reports.report(tenant,run);
    }
    private boolean managerCycle(UUID tenant,UUID run) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            with recursive edges as (
              select e.id,case when s.valid and e.source_version<s.source_version then s.manager_id else e.manager_id end as manager_id
              from employees e left join snapshot_staging s on s.tenant_id=e.tenant_id and s.run_id=? and s.valid and s.external_employee_id=e.external_employee_id
              where e.tenant_id=?
            ), walk as (
              select id,manager_id,array[id] as path,(manager_id=id) as cycle from edges where manager_id is not null
              union all
              select w.id,e.manager_id,w.path||e.id,e.id=any(w.path) from walk w join edges e on e.id=w.manager_id
              where not w.cycle and cardinality(w.path)<=1000
            ) select exists(select 1 from walk where cycle or cardinality(path)>1000)
            """,Boolean.class,run,tenant));
    }
}
