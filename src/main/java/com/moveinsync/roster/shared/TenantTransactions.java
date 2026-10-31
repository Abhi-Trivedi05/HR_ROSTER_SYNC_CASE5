package com.moveinsync.roster.shared;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class TenantTransactions {
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    public TenantTransactions(PlatformTransactionManager manager, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(120);
    }
    public <T> T inTenant(UUID tenant, boolean lock, Supplier<T> work) {
        if (tenant == null) throw ApiException.notFound();
        return transactions.execute(status -> {
            jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            var ids = jdbc.queryForList("select id from tenants where id=?" + (lock ? " for update" : ""), UUID.class, tenant);
            if (ids.isEmpty()) throw ApiException.notFound();
            return work.get();
        });
    }
}
