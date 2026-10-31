package com.moveinsync.roster.employee;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Includes tenant in Hibernate-generated update/delete predicates as well as explicit reads. */
public class EmployeeId implements Serializable {
    private UUID tenantId;
    private UUID id;
    public EmployeeId() { }
    public EmployeeId(UUID tenantId, UUID id) { this.tenantId = tenantId; this.id = id; }
    @Override public boolean equals(Object other) {
        return other instanceof EmployeeId that && Objects.equals(tenantId, that.tenantId) && Objects.equals(id, that.id);
    }
    @Override public int hashCode() { return Objects.hash(tenantId, id); }
}
