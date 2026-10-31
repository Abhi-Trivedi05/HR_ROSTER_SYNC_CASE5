package com.moveinsync.roster.office;

import com.moveinsync.roster.shared.TenantIdentity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/offices")
public class OfficeController {
    private final OfficeMetadataService service;private final TenantIdentity identity;private final OfficeManagementService management;
    public OfficeController(OfficeMetadataService service,TenantIdentity identity,OfficeManagementService management) { this.service=service;this.identity=identity;this.management=management; }
    @GetMapping("/{code}") public OfficeMetadataService.Metadata get(@AuthenticationPrincipal Jwt jwt,@PathVariable String code) { return service.get(identity.from(jwt),code); }
    @PatchMapping("/{code}") @org.springframework.security.access.prepost.PreAuthorize("hasRole('OPERATOR')")
    public void update(@AuthenticationPrincipal Jwt jwt,@PathVariable String code,@jakarta.validation.Valid @RequestBody OfficeManagementService.Update input) { management.update(identity.from(jwt),code,input); }
}
