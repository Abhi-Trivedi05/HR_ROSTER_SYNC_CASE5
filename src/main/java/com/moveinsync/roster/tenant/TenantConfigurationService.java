package com.moveinsync.roster.tenant;

import com.moveinsync.roster.cache.CacheInvalidator;
import com.moveinsync.roster.shared.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class TenantConfigurationService {
    public record Update(@NotNull @DecimalMin(value="0",inclusive=false) @DecimalMax("1") @Digits(integer=1,fraction=5) BigDecimal missingThreshold) { }
    private final JdbcTemplate jdbc;private final TenantTransactions transactions;private final CacheInvalidator cache;
    public TenantConfigurationService(JdbcTemplate jdbc,TenantTransactions transactions,CacheInvalidator cache) { this.jdbc=jdbc;this.transactions=transactions;this.cache=cache; }
    @PreAuthorize("hasRole('OPERATOR') and @tenantAccess.matches(#tenant)")
    public void update(UUID tenant,Update input) {
        if (input.missingThreshold()==null||input.missingThreshold().signum()<=0||input.missingThreshold().compareTo(BigDecimal.ONE)>0||input.missingThreshold().stripTrailingZeros().scale()>5) throw ApiException.invalid("Threshold must be in (0,1] with at most five decimal places");
        transactions.inTenant(tenant,true,()->jdbc.update("update tenants set missing_threshold=?,config_revision=config_revision+1 where id=?",input.missingThreshold(),tenant));
        cache.config(tenant);
    }
}
