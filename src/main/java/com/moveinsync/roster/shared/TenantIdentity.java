package com.moveinsync.roster.shared;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class TenantIdentity {
    public UUID employee(Jwt jwt) {
        try { return UUID.fromString(jwt.getClaimAsString("employee_id")); }
        catch (RuntimeException ex) { throw new ApiException(HttpStatus.FORBIDDEN,"Employee identity required"); }
    }
    public UUID from(Jwt jwt) {
        try { return UUID.fromString(jwt.getClaimAsString("tenant_id")); }
        catch (RuntimeException ex) { throw new ApiException(HttpStatus.FORBIDDEN, "Valid tenant identity required"); }
    }
}
