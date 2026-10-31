package com.moveinsync.roster.config;

import com.nimbusds.jose.jwk.*;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/auth") @ConditionalOnProperty(name="security.local.enabled",havingValue="true")
public class AuthController {
    private final AuthService service; private final RSAKey key;
    public AuthController(AuthService service,RSAKey key) { this.service=service;this.key=key; }
    @PostMapping("/token") public Map<String,Object> token(@Valid @RequestBody AuthService.Login input) { return service.token(input); }
    @GetMapping("/jwks") public Map<String,Object> jwks() { return new JWKSet(key.toPublicJWK()).toJSONObject(); }
}
