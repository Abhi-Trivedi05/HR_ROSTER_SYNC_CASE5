package com.moveinsync.roster.employee;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.Repository;

public interface EmployeeRepository extends Repository<Employee, EmployeeId> {
    Optional<Employee> findByTenantIdAndId(UUID tenantId, UUID id);
    @org.springframework.data.jpa.repository.Query("select e.revision from Employee e where e.tenantId=:tenantId and e.id=:id")
    Optional<Long> findRevisionByTenantIdAndId(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId,@org.springframework.data.repository.query.Param("id") UUID id);
    Slice<Employee> findAllByTenantId(UUID tenantId, Pageable pageable);
    boolean existsByTenantIdAndExternalEmployeeId(UUID tenantId, String externalEmployeeId);
    Employee save(Employee employee);
}
