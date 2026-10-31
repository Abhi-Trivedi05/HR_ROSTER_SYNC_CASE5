package com.moveinsync.roster.employee;

import com.moveinsync.roster.shared.ApiException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "employees")
@IdClass(EmployeeId.class)
public class Employee {
    @Id private UUID id;
    @Id @Column(nullable = false, updatable = false) private UUID tenantId;
    @Column(nullable = false, updatable = false, length = 80) private String externalEmployeeId;
    @Column(nullable = false, length = 160) private String name;
    @Column(nullable = false, length = 254) private String email;
    @Column(length = 20) private String phone;
    private UUID managerId;
    @Column(nullable = false, length = 40) private String officeCode;
    @Column(length = 300) private String pickupPoint;
    @Column(nullable = false) private boolean specialAssistance;
    @Column(nullable = false) private long sourceVersion;
    private UUID sourceEventId;
    @Column(nullable = false) private boolean active;
    @Version private Long revision;
    @Column(nullable=false) private long transportRevision;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected Employee() { }
    public static Employee create(UUID tenantId, EmployeeDtos.Create input, Instant now) {
        Employee e = new Employee();
        e.id = UUID.randomUUID(); e.tenantId = tenantId; e.externalEmployeeId = input.externalEmployeeId();
        e.name = input.name(); e.email = input.email(); e.phone = input.phone();
        e.managerId = input.managerId(); e.officeCode = input.officeCode(); e.active = true;
        e.createdAt = now; e.updatedAt = now;
        return e;
    }
    public void updateHr(EmployeeDtos.HrUpdate input, Instant now) {
        requireRevision(input.expectedRevision());
        name = input.name(); email = input.email(); phone = input.phone();
        managerId = input.managerId(); officeCode = input.officeCode(); updatedAt = now;
    }
    public void updateTransport(EmployeeDtos.TransportUpdate input, Instant now) {
        requireRevision(input.expectedRevision());
        pickupPoint = input.pickupPoint(); specialAssistance = input.specialAssistance(); updatedAt = now;
    }
    private void requireRevision(Long expected) {
        if (expected == null || !expected.equals(revision)) throw ApiException.conflict();
    }
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getExternalEmployeeId() { return externalEmployeeId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public UUID getManagerId() { return managerId; }
    public String getOfficeCode() { return officeCode; }
    public String getPickupPoint() { return pickupPoint; }
    public boolean isSpecialAssistance() { return specialAssistance; }
    public long getSourceVersion() { return sourceVersion; }
    public UUID getSourceEventId() { return sourceEventId; }
    public boolean isActive() { return active; }
    public long getRevision() { return revision == null ? 0 : revision; }
    public long getTransportRevision() { return transportRevision; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
