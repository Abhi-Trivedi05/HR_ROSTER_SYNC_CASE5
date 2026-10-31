package com.moveinsync.roster.config;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import static org.assertj.core.api.Assertions.*;

class LocalJwtConfigTest {
    static JwtEncoder encoder,otherEncoder;static JwtDecoder decoder;
    @BeforeAll static void setup() throws Exception {
        var config=new LocalJwtConfig();var key=config.localKey();encoder=config.jwtEncoder(key);otherEncoder=config.jwtEncoder(config.localKey());
        decoder=config.localDecoder(key,"https://fixture.invalid","roster-api");
    }
    String token(JwtEncoder signer,Instant expiry) {
        var claims=JwtClaimsSet.builder().issuer("https://fixture.invalid").subject("fixture").issuedAt(expiry.minusSeconds(600)).expiresAt(expiry)
                .audience(List.of("roster-api")).claim("tenant_id",UUID.randomUUID().toString()).claim("roles",List.of("EMPLOYEE")).build();
        return signer.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims)).getTokenValue();
    }
    @Test void validSignatureAccepted() { assertThat(decoder.decode(token(encoder,Instant.now().plusSeconds(300))).getSubject()).isEqualTo("fixture"); }
    @Test void expiredJwtRejected() { assertThatThrownBy(()->decoder.decode(token(encoder,Instant.now().minusSeconds(300)))).isInstanceOf(JwtException.class); }
    @Test void invalidSignatureRejected() { assertThatThrownBy(()->decoder.decode(token(otherEncoder,Instant.now().plusSeconds(300)))).isInstanceOf(JwtException.class); }
    @Test void malformedJwtRejected() { assertThatThrownBy(()->decoder.decode("not.a.valid.token")).isInstanceOf(JwtException.class); }
    @Test void bcryptHashesAndVerifiesPasswords() {
        var encoder=new BCryptPasswordEncoder(4);String hash=encoder.encode("fixture password");
        assertThat(hash).doesNotContain("fixture password");assertThat(encoder.matches("fixture password",hash)).isTrue();assertThat(encoder.matches("wrong",hash)).isFalse();
    }
}
