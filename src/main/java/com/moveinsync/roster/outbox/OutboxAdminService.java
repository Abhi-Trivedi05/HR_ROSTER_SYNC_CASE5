package com.moveinsync.roster.outbox;

import com.moveinsync.roster.shared.*;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class OutboxAdminService {
    private final OutboxRepository outbox;private final TenantTransactions transactions;
    public OutboxAdminService(OutboxRepository outbox,TenantTransactions transactions) { this.outbox=outbox;this.transactions=transactions; }
    @PreAuthorize("hasRole('OPERATOR') and @tenantAccess.matches(#tenant)")
    public void requeue(UUID tenant,UUID id) {
        transactions.inTenant(tenant,false,()->{if (outbox.requeue(tenant,id)!=1) throw ApiException.notFound();return null;});
    }
}
