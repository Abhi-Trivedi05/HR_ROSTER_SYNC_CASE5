package com.moveinsync.roster.config;

import java.util.UUID;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;

/** Additional claims; signature, issuer and time checks remain the decoder's responsibility. */
public final class RequiredJwtClaimsValidator implements OAuth2TokenValidator<Jwt> {
    private final String audience;
    public RequiredJwtClaimsValidator(String audience) { this.audience = audience; }
    @Override public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            String tenant = jwt.getClaimAsString("tenant_id");
            Object roles=jwt.getClaim("roles");
            if (!UUID.fromString(tenant).toString().equalsIgnoreCase(tenant)
                    || jwt.getExpiresAt() == null || !jwt.getAudience().contains(audience)
                    || jwt.getSubject()==null||jwt.getSubject().isBlank()||jwt.getSubject().length()>160
                    || !(roles instanceof java.util.Collection<?> values)||values.size()>20||values.stream().anyMatch(role->!(role instanceof String))) {
                return failure();
            }
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException ex) { return failure(); }
    }
    private OAuth2TokenValidatorResult failure() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required token claims missing or invalid", null));
    }
}
