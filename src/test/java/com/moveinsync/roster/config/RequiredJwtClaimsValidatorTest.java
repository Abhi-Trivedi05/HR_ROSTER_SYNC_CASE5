package com.moveinsync.roster.config;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;
import static org.assertj.core.api.Assertions.*;

class RequiredJwtClaimsValidatorTest {
    final RequiredJwtClaimsValidator validator = new RequiredJwtClaimsValidator("roster-api");
    Jwt.Builder token() {
        return Jwt.withTokenValue("test").header("alg", "RS256").subject("test-user")
                .claim("tenant_id", UUID.randomUUID().toString()).claim("roles",List.of("EMPLOYEE")).audience(List.of("roster-api"))
                .issuedAt(Instant.now().minusSeconds(60)).expiresAt(Instant.now().plusSeconds(300));
    }
    @Test void requiresAudience() {
        assertThat(validator.validate(token().audience(List.of("another-api")).build()).hasErrors()).isTrue();
    }
    @Test void requiresTenantUuid() {
        assertThat(validator.validate(token().claim("tenant_id", "invalid").build()).hasErrors()).isTrue();
        assertThat(validator.validate(token().claims(claims -> claims.remove("tenant_id")).build()).hasErrors()).isTrue();
    }
    @Test void requiresExpiry() {
        assertThat(validator.validate(token().claims(claims -> claims.remove("exp")).build()).hasErrors()).isTrue();
    }
    @Test void acceptsRequiredClaims() {
        assertThat(validator.validate(token().build()).hasErrors()).isFalse();
    }
    @Test void rejectsMalformedRolesAndMissingSubject() {
        assertThat(validator.validate(token().claim("roles",List.of(1)).build()).hasErrors()).isTrue();
        assertThat(validator.validate(token().claims(claims->claims.remove("sub")).build()).hasErrors()).isTrue();
    }
    @Test void defaultIssuerValidatorRejectsExpiredAndWrongIssuer() {
        var standard = JwtValidators.createDefaultWithIssuer("https://expected.example.invalid");
        assertThat(standard.validate(token().issuer("https://wrong.example.invalid").build()).hasErrors()).isTrue();
        assertThat(standard.validate(token().issuer("https://expected.example.invalid")
                .issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().minusSeconds(300)).build()).hasErrors()).isTrue();
    }
}
