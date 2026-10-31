package com.moveinsync.roster.employee;

import com.moveinsync.roster.cache.CacheInvalidator;
import com.moveinsync.roster.outbox.OutboxRepository;
import com.moveinsync.roster.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Service @Validated
public class TransportService {
    private final JdbcTemplate jdbc; private final TenantTransactions transactions; private final OutboxRepository outbox;
    private final CacheInvalidator caches;
    public TransportService(JdbcTemplate jdbc,TenantTransactions transactions,OutboxRepository outbox,CacheInvalidator caches) {
        this.jdbc=jdbc;this.transactions=transactions;this.outbox=outbox;this.caches=caches;
    }
    @PreAuthorize("hasRole('ADMIN') and @tenantAccess.matches(#tenant)")
    public long update(UUID tenant,UUID employee,@NotNull @Valid EmployeeDtos.TransportUpdate input) {
        long revision=transactions.inTenant(tenant,true,()-> {
            var revisions=jdbc.queryForList("select transport_revision from employees where tenant_id=? and id=? for update",Long.class,tenant,employee);
            if (revisions.isEmpty()) throw ApiException.notFound();
            if (!revisions.get(0).equals(input.expectedRevision())) throw ApiException.conflict();
            jdbc.update("update employees set pickup_point=?,special_assistance=?,transport_revision=transport_revision+1,revision=revision+1,updated_at=now() where tenant_id=? and id=?",
                    input.pickupPoint(),input.specialAssistance(),tenant,employee);
            long version=jdbc.queryForObject("select source_version from employees where tenant_id=? and id=?",Long.class,tenant,employee);
            outbox.append(tenant,employee,"EMPLOYEE_TRANSPORT_UPDATED",version);
            return revisions.get(0)+1;
        });
        caches.employee(tenant,employee); return revision;
    }
}
