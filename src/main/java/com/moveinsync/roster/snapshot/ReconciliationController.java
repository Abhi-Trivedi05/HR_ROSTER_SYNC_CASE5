package com.moveinsync.roster.snapshot;

import com.moveinsync.roster.shared.TenantIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/reconciliations") @PreAuthorize("hasRole('OPERATOR')")
public class ReconciliationController {
    private final ReconciliationService service; private final TenantIdentity identity;
    public ReconciliationController(ReconciliationService service,TenantIdentity identity) { this.service=service;this.identity=identity; }
    @PostMapping public SnapshotModels.Report create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody SnapshotModels.Create input) { return service.create(identity.from(jwt),input); }
    @GetMapping("/{id}") public SnapshotModels.Report report(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return service.report(identity.from(jwt),id); }
    @GetMapping("/{id}/errors") public java.util.List<ReconciliationReportService.RowError> errors(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestParam(defaultValue="0") long afterRow,@RequestParam(defaultValue="100") int limit) { return service.errors(identity.from(jwt),id,afterRow,limit); }
    @PutMapping(value="/{id}/csv",consumes="text/csv") public SnapshotModels.Report upload(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,HttpServletRequest request) throws IOException { return service.upload(identity.from(jwt),id,request.getInputStream()); }
    @PostMapping("/{id}/approve") public SnapshotModels.Report approve(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody SnapshotModels.Approval input) { return service.approve(identity.from(jwt),id,input,jwt.getSubject()); }
    @PostMapping("/{id}/apply") public SnapshotModels.Report apply(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return service.apply(identity.from(jwt),id); }
    @PostMapping("/{id}/refresh") public SnapshotModels.Report refresh(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return service.refresh(identity.from(jwt),id); }
}
