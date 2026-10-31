package com.moveinsync.roster.office;

import com.moveinsync.roster.cache.CacheInvalidator;
import com.moveinsync.roster.shared.*;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class OfficeManagementService {
    public record Update(@NotBlank @Size(max=160) String name,@NotNull Boolean active) { }
    private final JdbcTemplate jdbc;private final TenantTransactions transactions;private final CacheInvalidator caches;
    public OfficeManagementService(JdbcTemplate jdbc,TenantTransactions transactions,CacheInvalidator caches) { this.jdbc=jdbc;this.transactions=transactions;this.caches=caches; }
    @PreAuthorize("hasRole('OPERATOR') and @tenantAccess.matches(#tenant)")
    public void update(UUID tenant,String code,Update update) {
        transactions.inTenant(tenant,true,()-> {
            if(jdbc.update("update offices set name=?,active=? where tenant_id=? and code=?",update.name().strip(),update.active(),tenant,code)!=1)throw ApiException.notFound();
            jdbc.update("update tenants set hr_revision=hr_revision+1 where id=?",tenant);return null;
        });
        caches.office(tenant,code);
    }
}
