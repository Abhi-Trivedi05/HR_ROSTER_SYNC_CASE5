package com.moveinsync.roster.tenant;

import com.moveinsync.roster.shared.TenantIdentity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/tenant/config")
public class TenantConfigurationController {
    private final TenantConfigurationService service;private final TenantIdentity identity;
    public TenantConfigurationController(TenantConfigurationService service,TenantIdentity identity) { this.service=service;this.identity=identity; }
    @PatchMapping @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('OPERATOR')")
    public void update(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody TenantConfigurationService.Update input) { service.update(identity.from(jwt),input); }
}
