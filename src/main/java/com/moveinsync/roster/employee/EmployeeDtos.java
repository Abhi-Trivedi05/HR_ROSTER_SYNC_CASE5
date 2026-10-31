package com.moveinsync.roster.employee;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EmployeeDtos {
    private EmployeeDtos() { }
    public record Create(
            @NotBlank @Size(max = 80) String externalEmployeeId,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @Pattern(regexp = "\\+?[0-9]{7,15}") String phone,
            UUID managerId,
            @NotBlank @Size(max = 40) String officeCode) {
        public Create { externalEmployeeId = trim(externalEmployeeId); name = trim(name); email = trim(email); phone = trim(phone); officeCode = trim(officeCode); }
        @Override public String toString() { return "Create[redacted]"; }
    }
    public record HrUpdate(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @Pattern(regexp = "\\+?[0-9]{7,15}") String phone,
            UUID managerId,
            @NotBlank @Size(max = 40) String officeCode,
            @NotNull @PositiveOrZero Long expectedRevision) {
        public HrUpdate { name = trim(name); email = trim(email); phone = trim(phone); officeCode = trim(officeCode); }
        @Override public String toString() { return "HrUpdate[redacted]"; }
    }
    public record TransportUpdate(
            @Size(max = 300) String pickupPoint,
            @NotNull Boolean specialAssistance,
            @NotNull @PositiveOrZero Long expectedRevision) {
        public TransportUpdate { pickupPoint = trim(pickupPoint); }
        @Override public String toString() { return "TransportUpdate[redacted]"; }
    }
    public record Response(UUID id, UUID tenantId, String externalEmployeeId, String name,
            String email, String phone, UUID managerId, String officeCode, String pickupPoint,
            boolean specialAssistance, long sourceVersion, UUID sourceEventId, boolean active,
            Instant createdAt, Instant updatedAt, long revision, long transportRevision) {
        public static Response from(Employee e) {
            return new Response(e.getId(), e.getTenantId(), e.getExternalEmployeeId(), e.getName(),
                    e.getEmail(), e.getPhone(), e.getManagerId(), e.getOfficeCode(), e.getPickupPoint(),
                    e.isSpecialAssistance(), e.getSourceVersion(), e.getSourceEventId(), e.isActive(),
                    e.getCreatedAt(), e.getUpdatedAt(), e.getRevision(), e.getTransportRevision());
        }
        @Override public String toString() { return "EmployeeResponse[redacted]"; }
    }
    public record Page(List<Response> items, int page, int size, boolean hasNext) {
        public Page { items = List.copyOf(items); }
        @Override public String toString() { return "EmployeePage[redacted]"; }
    }
    private static String trim(String value) { return value == null ? null : value.strip(); }
}
