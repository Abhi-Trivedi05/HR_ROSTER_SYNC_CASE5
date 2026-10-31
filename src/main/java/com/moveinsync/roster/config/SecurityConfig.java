package com.moveinsync.roster.config;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.shared.ApiError;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean @ConditionalOnProperty(name="security.local.enabled",havingValue="false",matchIfMissing=true)
    JwtDecoder jwtDecoder(@Value("${security.jwt.issuer}") String issuer,
            @Value("${security.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${security.jwt.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                new RequiredJwtClaimsValidator(audience)));
        return decoder;
    }
    @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain securityFilterChain(HttpSecurity http,ObjectMapper mapper) throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter authentication = new JwtAuthenticationConverter();
        authentication.setJwtGrantedAuthoritiesConverter(authorities);
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/auth/token","/api/v1/auth/jwks").permitAll()
                        .requestMatchers("/actuator/**").hasRole("OPERATOR")
                        .requestMatchers("/api/v1/hr/**").hasRole("HR_INTEGRATION")
                        .requestMatchers("/api/v1/reconciliations/**","/api/v1/tenant/**","/api/v1/outbox/**").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.PATCH,"/api/v1/employees/*/transport").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors->errors
                        .authenticationEntryPoint((request,response,error)->{ response.setStatus(401);response.setContentType("application/json");mapper.writeValue(response.getOutputStream(),ApiError.of("UNAUTHENTICATED","Authentication required")); })
                        .accessDeniedHandler((request,response,error)->{ response.setStatus(403);response.setContentType("application/json");mapper.writeValue(response.getOutputStream(),ApiError.of("FORBIDDEN","Access denied")); }))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(authentication))
                        .authenticationEntryPoint((request,response,error)->{response.setStatus(401);response.setContentType("application/json");mapper.writeValue(response.getOutputStream(),ApiError.of("INVALID_TOKEN","Valid authentication required"));}))
                .build();
    }
}
