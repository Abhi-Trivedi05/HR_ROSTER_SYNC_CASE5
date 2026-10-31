package com.moveinsync.roster.config;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component("tenantAccess")
public class TenantAccess {
    public boolean matches(UUID tenant) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) return false;
        return tenant != null && tenant.toString().equalsIgnoreCase(jwt.getClaimAsString("tenant_id"));
    }
    public boolean employee(UUID tenant, UUID id) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!matches(tenant)) return false;
        boolean privileged = authentication.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_OPERATOR"));
        return privileged || id.toString().equals(((Jwt) authentication.getPrincipal()).getClaimAsString("employee_id"));
    }
}
