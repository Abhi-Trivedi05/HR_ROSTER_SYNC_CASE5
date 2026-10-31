package com.moveinsync.roster.ingestion;

import com.moveinsync.roster.shared.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.moveinsync.roster.ingestion.EventResult.Status.*;

@Component
public class EventProcessor {
    private final TenantTransactions transactions;
    private final JdbcTemplate jdbc;
    private final EventValidator validator;
    private final EmployeeMutationRepository employees;
    private final FailureProbe failures;
    public EventProcessor(TenantTransactions transactions,JdbcTemplate jdbc,EventValidator validator,
            EmployeeMutationRepository employees,FailureProbe failures) {
        this.transactions=transactions;this.jdbc=jdbc;this.validator=validator;this.employees=employees;this.failures=failures;
    }
    public EventResult process(UUID tenant,HrEvent event,int index) {
        var structure=validator.structure(event);
        if (!structure.valid()) return EventResult.of(index,event==null?null:event.eventId(),REJECTED,structure.errorCode());
        return transactions.inTenant(tenant,true,()-> {
            String hash=event.hash();
            var receipt=jdbc.queryForList("select payload_hash from event_receipts where tenant_id=? and event_id=?",String.class,tenant,event.eventId());
            if (!receipt.isEmpty()) return EventResult.of(index,event.eventId(),receipt.get(0).equals(hash)?DUPLICATE:REJECTED,
                    receipt.get(0).equals(hash)?"NONE":"EVENT_ID_CONFLICT");
            var canonical=jdbc.queryForList("select payload_hash from processed_events where tenant_id=? and external_employee_id=? and source_version=?",
                    String.class,tenant,event.employeeId(),event.version());
            if (!canonical.isEmpty()) {
                if (!canonical.get(0).equals(hash)) return EventResult.of(index,event.eventId(),REJECTED,"VERSION_CONFLICT");
                receipt(tenant,event,hash);
                return EventResult.of(index,event.eventId(),DUPLICATE,"NONE");
            }
            var previous=employees.find(tenant,event.employeeId());
            if (previous.isPresent() && event.version()<=previous.get().version()) {
                // Legacy/snapshot rows may have no event receipt. A matching HR state at equal version is still a no-op.
                if (event.version()==previous.get().version()) {
                    boolean same=event.eventType()==HrEvent.Type.DEACTIVATE ? !previous.get().active()
                            : event.data().hash().equals(previous.get().hash());
                    if (!same) return EventResult.of(index,event.eventId(),REJECTED,"VERSION_CONFLICT");
                    record(tenant,event,hash,"DUPLICATE");
                    return EventResult.of(index,event.eventId(),DUPLICATE,"NONE");
                }
                record(tenant,event,hash,"STALE");
                return EventResult.of(index,event.eventId(),STALE,"NONE");
            }
            var reference=validator.references(tenant,event);
            if (!reference.valid()) return EventResult.of(index,event.eventId(),REJECTED,reference.errorCode());
            employees.apply(tenant,event,previous);
            record(tenant,event,hash,"APPLIED");
            failures.at("event-before-commit");
            return EventResult.of(index,event.eventId(),APPLIED,"NONE");
        });
    }
    private void record(UUID tenant,HrEvent event,String hash,String outcome) {
        jdbc.update("insert into processed_events(tenant_id,external_employee_id,source_version,payload_hash,outcome,source_timestamp) values (?,?,?,?,?,?)",
                tenant,event.employeeId(),event.version(),hash,outcome,java.sql.Timestamp.from(event.timestamp()));
        receipt(tenant,event,hash);
    }
    private void receipt(UUID tenant,HrEvent event,String hash) {
        jdbc.update("insert into event_receipts(tenant_id,event_id,external_employee_id,source_version,payload_hash) values (?,?,?,?,?)",
                tenant,event.eventId(),event.employeeId(),event.version(),hash);
    }
}
