package com.moveinsync.roster.outbox;

import com.moveinsync.roster.shared.TenantIdentity;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/outbox")
public class OutboxAdminController {
    private final OutboxAdminService service;private final TenantIdentity identity;
    public OutboxAdminController(OutboxAdminService service,TenantIdentity identity) { this.service=service;this.identity=identity; }
    @PostMapping("/{id}/requeue") public void requeue(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { service.requeue(identity.from(jwt),id); }
}
