package com.moveinsync.roster.employee;

import com.moveinsync.roster.shared.ApiException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ManagerValidatorTest {
    final EmployeeRepository employees = mock(EmployeeRepository.class);
    final ManagerValidator validator = new ManagerValidator(employees);
    final UUID tenant = UUID.randomUUID();
    @Test void selfManagementRejectedWithoutLookup() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> validator.validate(tenant, id, id)).isInstanceOf(ApiException.class);
        verifyNoInteractions(employees);
    }
    @Test void cycleRejectedAndLookupsScoped() {
        UUID employeeId = UUID.randomUUID();
        Employee manager = Employee.create(tenant, new EmployeeDtos.Create("manager", "Manager", "m@example.invalid", null, employeeId, "BLR"), Instant.now());
        when(employees.findByTenantIdAndId(tenant, manager.getId())).thenReturn(Optional.of(manager));
        assertThatThrownBy(() -> validator.validate(tenant, employeeId, manager.getId())).isInstanceOf(ApiException.class);
        verify(employees).findByTenantIdAndId(tenant, manager.getId());
    }
    @Test void managerFromAnotherTenantIsNotFound() {
        assertThatThrownBy(() -> validator.validate(tenant, UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(ApiException.class);
    }
}
