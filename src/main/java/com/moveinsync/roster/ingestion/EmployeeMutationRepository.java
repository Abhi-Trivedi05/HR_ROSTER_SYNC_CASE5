package com.moveinsync.roster.ingestion;

import com.moveinsync.roster.outbox.OutboxRepository;
import com.moveinsync.roster.shared.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Only explicit HR column assignments; callers hold the tenant lock in a transaction. */
@Repository
public class EmployeeMutationRepository {
    public record Current(UUID id, long version, boolean active, String hash) { }
    private final JdbcTemplate jdbc;
    private final OutboxRepository outbox;
    private final FailureProbe failures;
    public EmployeeMutationRepository(JdbcTemplate jdbc, OutboxRepository outbox, FailureProbe failures) {
        this.jdbc=jdbc; this.outbox=outbox; this.failures=failures;
    }
    public Optional<Current> find(UUID tenant, String external) {
        return jdbc.query("select id,source_version,active,hr_hash from employees where tenant_id=? and external_employee_id=? for update",
                (rs,n) -> new Current(rs.getObject(1,UUID.class),rs.getLong(2),rs.getBoolean(3),rs.getString(4)),tenant,external).stream().findFirst();
    }
    public void validateManager(UUID tenant, UUID employee, UUID manager) {
        Set<UUID> visited=new HashSet<>();
        while (manager!=null) {
            if (manager.equals(employee) || !visited.add(manager) || visited.size()>1000) throw new EventRejected("INVALID_MANAGER_HIERARCHY");
            var rows=jdbc.query("select manager_id,active from employees where tenant_id=? and id=?",
                    (rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getBoolean(2)},tenant,manager);
            if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0)[1])) throw new EventRejected("INVALID_MANAGER");
            manager=(UUID)rows.get(0)[0];
        }
    }
    public UUID apply(UUID tenant,HrEvent event,Optional<Current> previous) {
        UUID id=previous.map(Current::id).orElseGet(UUID::randomUUID);
        if (event.eventType()==HrEvent.Type.DEACTIVATE) {
            if (previous.isEmpty()) throw new EventRejected("EMPLOYEE_NOT_FOUND");
            jdbc.update("update employees set active=false,source_version=?,source_event_id=?,revision=revision+1,updated_at=now(),hr_updated_at=now(),deactivated_at=now() where tenant_id=? and id=?",
                    event.version(),event.eventId(),tenant,id);
            // Recompute canonical HR state with active=false in Java; no transport fields participate.
            var data=jdbc.queryForObject("select name,email,phone,manager_id,office_code from employees where tenant_id=? and id=?",
                    (rs,n)->new HrEvent.Data(rs.getString(1),rs.getString(2),rs.getString(3),rs.getObject(4,UUID.class),rs.getString(5),false),tenant,id);
            jdbc.update("update employees set hr_hash=? where tenant_id=? and id=?",data.hash(),tenant,id);
        } else {
            var data=event.data(); validateManager(tenant,id,data.managerId());
            jdbc.update("""
                insert into employees(id,tenant_id,external_employee_id,name,email,phone,manager_id,office_code,
                  active,source_version,source_event_id,hr_hash,created_at,updated_at,deactivated_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,now(),now(),case when ? then null else now() end)
                on conflict (tenant_id,external_employee_id) do update set name=excluded.name,email=excluded.email,
                  phone=excluded.phone,manager_id=excluded.manager_id,office_code=excluded.office_code,active=excluded.active,
                  source_version=excluded.source_version,source_event_id=excluded.source_event_id,hr_hash=excluded.hr_hash,
                  updated_at=now(),hr_updated_at=now(),revision=employees.revision+1,deactivated_at=excluded.deactivated_at
                """,id,tenant,event.employeeId(),data.name(),data.email(),data.phone(),data.managerId(),data.officeCode(),
                    data.active(),event.version(),event.eventId(),data.hash(),data.active());
        }
        failures.at("employee-written");
        jdbc.update("update tenants set hr_revision=hr_revision+1 where id=?",tenant);
        boolean inactive=event.eventType()==HrEvent.Type.DEACTIVATE || !event.data().active();
        outbox.append(tenant,id,inactive?"EMPLOYEE_DEACTIVATED":"EMPLOYEE_UPDATED",event.version());
        return id;
    }
    public static class EventRejected extends RuntimeException {
        private final String code;
        public EventRejected(String code) { super(code); this.code=code; }
        public String code() { return code; }
    }
}
