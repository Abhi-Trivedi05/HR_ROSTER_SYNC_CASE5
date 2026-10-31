package com.moveinsync.roster.employee;

import com.moveinsync.roster.shared.TenantIdentity;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/employees")
public class EmployeeController {
    private final EmployeeService service;private final TransportService transport;private final TenantIdentity identity;
    public EmployeeController(EmployeeService service,TransportService transport,TenantIdentity identity) { this.service=service;this.transport=transport;this.identity=identity; }
    @GetMapping("/{id}") @PreAuthorize("hasAnyRole('EMPLOYEE','ADMIN','OPERATOR')")
    public EmployeeDtos.Response get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return service.get(identity.from(jwt),id); }
    @GetMapping @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public EmployeeDtos.Page list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) { return service.list(identity.from(jwt),page,size); }
    @PatchMapping("/{id}/transport") @PreAuthorize("hasRole('ADMIN')")
    public Map<String,Long> updateTransport(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody EmployeeDtos.TransportUpdate input) {
        return Map.of("transportRevision",transport.update(identity.from(jwt),id,input));
    }
}
