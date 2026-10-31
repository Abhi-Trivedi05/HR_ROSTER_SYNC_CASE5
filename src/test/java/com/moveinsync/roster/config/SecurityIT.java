package com.moveinsync.roster.config;

import com.moveinsync.roster.IntegrationSupport;
import com.moveinsync.roster.employee.*;
import com.moveinsync.roster.ingestion.HrEvent;
import com.moveinsync.roster.snapshot.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SecurityIT extends IntegrationSupport {
    @Autowired EmployeeService employees;
    @Autowired TransportService transport;
    @Autowired ReconciliationService snapshots;
    @Test void tenantACannotReadOrUpdateTenantB() {
        submit(event("E",1,HrEvent.Type.CREATE));UUID id=employeeId("E"),other=UUID.randomUUID();
        jdbc.update("insert into tenants(id,name) values (?,'Other')",other);
        authenticate(other,"ADMIN",null);
        assertThatThrownBy(()->employees.get(tenant,id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->transport.update(tenant,id,new EmployeeDtos.TransportUpdate("Gate",true,0L))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->employees.get(other,id)).isInstanceOf(com.moveinsync.roster.shared.ApiException.class);
    }
    @Test void employeeSeesOnlyTheirOwnProfile() {
        submit(event("ONE",1,HrEvent.Type.CREATE));submit(event("TWO",1,HrEvent.Type.CREATE));UUID own=employeeId("ONE"),other=employeeId("TWO");
        authenticate(tenant,"EMPLOYEE",own);
        assertThat(employees.get(tenant,own).id()).isEqualTo(own);
        assertThatThrownBy(()->employees.get(tenant,other)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->employees.list(tenant,0,50)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->transport.update(tenant,own,new EmployeeDtos.TransportUpdate(null,false,0L))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void adminCannotApproveButOperatorCanCreateRun() {
        authenticate(tenant,"ADMIN",null);
        assertThatThrownBy(()->snapshots.create(tenant,new SnapshotModels.Create("a".repeat(64),1L,Instant.now()))).isInstanceOf(AccessDeniedException.class);
        authenticate(tenant,"OPERATOR",null);
        assertThat(snapshots.create(tenant,new SnapshotModels.Create("a".repeat(64),1L,Instant.now())).status()).isEqualTo("STAGING");
    }
    @Test void realEndpointRejectsCrossTenantAndBodyMassAssignment() throws Exception {
        submit(event("ONE",1,HrEvent.Type.CREATE));UUID id=employeeId("ONE"),other=UUID.randomUUID();
        jdbc.update("insert into tenants(id,name) values (?,'Other')",other);
        mvc.perform(get("/api/v1/employees/"+id).with(jwt().jwt(j->j.claim("tenant_id",other.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/employees/"+id+"/transport").with(jwt().jwt(j->j.claim("tenant_id",other.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType("application/json").content("{\"pickupPoint\":null,\"specialAssistance\":false,\"expectedRevision\":0}"))
                .andExpect(status().isNotFound());
    }
}
