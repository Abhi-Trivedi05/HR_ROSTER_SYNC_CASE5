package com.moveinsync.roster.config;

import com.moveinsync.roster.shared.ApiException;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.stereotype.Service;

@Service @ConditionalOnProperty(name="security.local.enabled",havingValue="true")
public class AuthService {
    public record Login(@NotBlank @Size(max=120) String username,@NotBlank @Size(max=72) String password) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    private final JdbcTemplate jdbc;private final PasswordEncoder passwords;private final JwtEncoder encoder;
    private final String issuer,audience,dummy;
    public AuthService(JdbcTemplate jdbc,PasswordEncoder passwords,JwtEncoder encoder,
            @Value("${security.jwt.issuer}") String issuer,@Value("${security.jwt.audience}") String audience) {
        this.jdbc=jdbc;this.passwords=passwords;this.encoder=encoder;this.issuer=issuer;this.audience=audience;
        dummy=passwords.encode(UUID.randomUUID().toString());
    }
    public Map<String,Object> token(Login login) {
        if(login.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw denied();
        UUID tenant;String username;
        try {
            int colon=login.username().indexOf(':');tenant=UUID.fromString(login.username().substring(0,colon));username=login.username().substring(colon+1);
        } catch (RuntimeException ex) { passwords.matches(login.password(),dummy);throw denied(); }
        var rows=jdbc.queryForList("select id,password_hash,role,employee_id from app_users where tenant_id=? and username=?",tenant,username);
        String hash=rows.isEmpty()?dummy:(String)rows.getFirst().get("password_hash");
        if (!passwords.matches(login.password(),hash)||rows.isEmpty()) throw denied();
        var row=rows.getFirst();Instant now=Instant.now();
        var claims=JwtClaimsSet.builder().issuer(issuer).subject(row.get("id").toString()).issuedAt(now).expiresAt(now.plusSeconds(900))
                .audience(List.of(audience)).claim("tenant_id",tenant.toString()).claim("roles",List.of(row.get("role")));
        if (row.get("employee_id")!=null) claims.claim("employee_id",row.get("employee_id").toString());
        String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims.build())).getTokenValue();
        return Map.of("accessToken",token,"tokenType","Bearer","expiresIn",900);
    }
    private ApiException denied() { return new ApiException(HttpStatus.UNAUTHORIZED,"Invalid credentials"); }
}
