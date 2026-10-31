package com.moveinsync.roster.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.roster.shared.TenantIdentity;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/hr/events")
@PreAuthorize("hasRole('HR_INTEGRATION')")
public class HrEventController {
    private final BatchProcessingService service;
    private final TenantIdentity identity;
    public HrEventController(BatchProcessingService service,TenantIdentity identity) { this.service=service;this.identity=identity; }
    @PostMapping public EventResult event(@AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode record) {
        return service.process(identity.from(jwt),Collections.singletonList(record)).get(0);
    }
    @PostMapping("/batch") public List<EventResult> batch(@AuthenticationPrincipal Jwt jwt,@RequestBody List<JsonNode> records) {
        return service.process(identity.from(jwt),records);
    }
}
