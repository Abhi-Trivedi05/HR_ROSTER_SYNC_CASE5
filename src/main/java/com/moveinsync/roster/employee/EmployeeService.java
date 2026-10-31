package com.moveinsync.roster.employee;

import com.moveinsync.roster.cache.ProfileCache;
import com.moveinsync.roster.shared.ApiException;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class EmployeeService {
    private final EmployeeRepository employees;
    private final ProfileCache cache;
    public EmployeeService(EmployeeRepository employees,ProfileCache cache) { this.employees=employees;this.cache=cache; }
    @PreAuthorize("hasAnyRole('EMPLOYEE','ADMIN','OPERATOR') and @tenantAccess.employee(#tenantId,#id)")
    public EmployeeDtos.Response get(UUID tenantId,UUID id) {
        long revision=employees.findRevisionByTenantIdAndId(tenantId,id).orElseThrow(ApiException::notFound);
        return cache.get(tenantId,"employee",id.toString(),Long.toString(revision),EmployeeDtos.Response.class,
                ()->EmployeeDtos.Response.from(employees.findByTenantIdAndId(tenantId,id).orElseThrow(ApiException::notFound)));
    }
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR') and @tenantAccess.matches(#tenantId)")
    public EmployeeDtos.Page list(UUID tenantId,int page,int size) {
        if (page<0||size<1||size>200||(long)page*size>Integer.MAX_VALUE) throw ApiException.invalid("Invalid page bounds");
        var slice=employees.findAllByTenantId(tenantId,PageRequest.of(page,size,Sort.by("id")));
        return new EmployeeDtos.Page(slice.map(EmployeeDtos.Response::from).getContent(),page,size,slice.hasNext());
    }
}
