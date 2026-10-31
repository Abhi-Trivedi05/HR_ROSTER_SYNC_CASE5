package com.moveinsync.roster.snapshot;

import com.moveinsync.roster.shared.TenantCatalog;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SnapshotRecovery {
    private final JdbcTemplate jdbc; private final TenantCatalog tenants; private final ReconciliationService service; private final boolean enabled;
    public SnapshotRecovery(JdbcTemplate jdbc,TenantCatalog tenants,ReconciliationService service,@Value("${roster.snapshot.recovery-enabled:true}") boolean enabled) {
        this.jdbc=jdbc;this.tenants=tenants;this.service=service;this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${roster.snapshot.recovery-ms:30000}")
    public void recover() {
        if (!enabled) return;
        try {
            tenants.each(tenant->{
                var runs=jdbc.queryForList("select id from reconciliation_runs where tenant_id=? and status in ('STAGING','FAILED') and (lease_until is null or lease_until<now()) and recovery_attempts<5 order by created_at limit 5",UUID.class,tenant);
                for (UUID run:runs) if (service.artifactExists(tenant,run)) try { service.recover(tenant,run); } catch (RuntimeException ignored) { }
            });
        } catch (RuntimeException ignored) { /* next polling cycle retries infrastructure failures */ }
    }
}
