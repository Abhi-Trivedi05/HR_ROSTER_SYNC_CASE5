package com.moveinsync.roster.employee;

import com.moveinsync.roster.shared.ApiException;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ManagerValidator {
    private final EmployeeRepository employees;
    public ManagerValidator(EmployeeRepository employees) { this.employees = employees; }
    // Caller holds tenant lock: concurrent assignments cannot both pass cycle validation.
    public void validate(UUID tenantId, UUID employeeId, UUID managerId) {
        var visited = new HashSet<UUID>();
        UUID cursor = managerId;
        while (cursor != null) {
            if (cursor.equals(employeeId) || !visited.add(cursor) || visited.size() > 1000) {
                throw ApiException.invalid("Invalid or excessively deep manager hierarchy");
            }
            Employee manager = employees.findByTenantIdAndId(tenantId, cursor).orElseThrow(ApiException::notFound);
            if (!manager.isActive()) throw ApiException.invalid("Manager must be active");
            cursor = manager.getManagerId();
        }
    }
}
