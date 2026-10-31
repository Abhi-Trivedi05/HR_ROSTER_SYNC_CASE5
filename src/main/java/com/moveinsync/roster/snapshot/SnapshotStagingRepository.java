package com.moveinsync.roster.snapshot;

import com.moveinsync.roster.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SnapshotStagingRepository {
    private final JdbcTemplate jdbc;
    private final TenantTransactions transactions;
    public SnapshotStagingRepository(JdbcTemplate jdbc,TenantTransactions transactions) { this.jdbc=jdbc;this.transactions=transactions; }
    public void heartbeat(UUID tenant,UUID run,UUID token) {
        int changed=jdbc.update("update reconciliation_runs set lease_until=now()+interval '2 minutes',updated_at=now() where tenant_id=? and id=? and staging_token=? and status='STAGING'",
                tenant,run,token);
        if (changed!=1) throw ApiException.conflict();
    }
    public void insert(UUID tenant,UUID run,UUID token,List<SnapshotModels.Row> rows) {
        transactions.inTenant(tenant,false,()-> {
            heartbeat(tenant,run,token);
            List<Object[]> args=new ArrayList<>(rows.size());
            for (var row:rows) {
                var data=row.data();
                args.add(new Object[]{tenant,run,row.number(),row.employeeId(),data==null?null:data.name(),data==null?null:data.email(),
                        data==null?null:data.phone(),data==null?null:data.managerId(),data==null?null:data.officeCode(),data==null?null:data.active(),
                        row.hash(),row.bucket(),row.valid(),row.valid()?null:row.errorCode(),row.version()});
            }
            jdbc.batchUpdate("insert into snapshot_staging(tenant_id,run_id,row_number,external_employee_id,name,email,phone,manager_id,office_code,active,hr_hash,bucket,valid,error_code,source_version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",args);
            // Set-based bounded-batch reference validation avoids a query per row.
            long start=rows.getFirst().number(),end=rows.getLast().number();
            jdbc.update("""
                update snapshot_staging s set valid=false,error_code='INVALID_OFFICE'
                where s.tenant_id=? and s.run_id=? and s.row_number between ? and ? and s.valid and not exists
                (select 1 from offices o where o.tenant_id=s.tenant_id and o.code=s.office_code and o.active)
                """,tenant,run,start,end);
            jdbc.update("""
                update snapshot_staging s set valid=false,error_code='INVALID_MANAGER'
                where s.tenant_id=? and s.run_id=? and s.row_number between ? and ? and s.valid and s.manager_id is not null and not exists
                (select 1 from employees e where e.tenant_id=s.tenant_id and e.id=s.manager_id and e.active)
                """,tenant,run,start,end);
            return null;
        });
    }
    public void duplicates(UUID tenant,UUID run) {
        jdbc.update("""
            with duplicates as (select external_employee_id from snapshot_staging where tenant_id=? and run_id=?
              and external_employee_id is not null group by external_employee_id having count(*)>1)
            update snapshot_staging s set valid=false,error_code='DUPLICATE_EMPLOYEE_ID' from duplicates d
            where s.tenant_id=? and s.run_id=? and s.external_employee_id=d.external_employee_id
            """,tenant,run,tenant,run);
    }
}
