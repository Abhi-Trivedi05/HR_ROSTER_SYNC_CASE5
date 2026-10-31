package com.moveinsync.roster.employee;

import com.moveinsync.roster.config.*;
import com.moveinsync.roster.shared.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@WebMvcTest(EmployeeController.class)
@Import({SecurityConfig.class,TenantIdentity.class,TenantAccess.class,JsonConfig.class,ApiExceptionHandler.class,CorrelationFilter.class})
class EmployeeControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean EmployeeService service;
    @MockitoBean TransportService transport;
    @MockitoBean JwtDecoder jwtDecoder;
    final UUID tenant=UUID.randomUUID(),employee=UUID.randomUUID();
    @Test void unauthenticatedIs401() throws Exception { mvc.perform(get("/api/v1/employees/"+employee)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").exists()).andExpect(jsonPath("$.correlationId").exists()); }
    @Test void employeeCannotUseAdminEndpoint() throws Exception {
        mvc.perform(patch("/api/v1/employees/"+employee+"/transport").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                .contentType("application/json").content("{\"pickupPoint\":null,\"specialAssistance\":true,\"expectedRevision\":0}"))
                .andExpect(status().isForbidden());verifyNoInteractions(transport);
    }
    @Test void massAssignmentRejectedWithoutEcho() throws Exception {
        mvc.perform(patch("/api/v1/employees/"+employee+"/transport").with(jwt().jwt(j->j.claim("tenant_id",tenant.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType("application/json").content("{\"pickupPoint\":null,\"specialAssistance\":true,\"expectedRevision\":0,\"name\":\"Private fixture\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").exists())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Private fixture"))));
        verifyNoInteractions(transport);
    }
    @Test void adminCannotApproveSnapshotAtSecurityLayer() throws Exception {
        mvc.perform(post("/api/v1/reconciliations/"+UUID.randomUUID()+"/approve").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType("application/json").content("{}" )).andExpect(status().isForbidden());
    }
    @Test void transportDtoCannotChangeSourceVersion() throws Exception {
        mvc.perform(patch("/api/v1/employees/"+employee+"/transport").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType("application/json").content("{\"specialAssistance\":false,\"expectedRevision\":0,\"sourceVersion\":999}"))
                .andExpect(status().isBadRequest());verifyNoInteractions(transport);
    }
    @Test void tenantComesFromVerifiedToken() throws Exception {
        mvc.perform(get("/api/v1/employees/"+employee).header("X-Tenant-Id",UUID.randomUUID())
                .with(jwt().jwt(j->j.claim("tenant_id",tenant.toString())).authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
                .andExpect(status().isOk());verify(service).get(tenant,employee);
    }
    @Test void invalidBearerIs401() throws Exception {
        when(jwtDecoder.decode("invalid")).thenThrow(new org.springframework.security.oauth2.jwt.BadJwtException("invalid"));
        mvc.perform(get("/api/v1/employees/"+employee).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
    }
}
