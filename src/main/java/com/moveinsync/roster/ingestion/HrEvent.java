package com.moveinsync.roster.ingestion;

import com.moveinsync.roster.shared.CanonicalHash;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public record HrEvent(@NotNull UUID eventId, @NotBlank @Size(max=80) String employeeId,
        @NotNull @PositiveOrZero Long version, @NotNull Type eventType,
        @NotNull Instant timestamp, @Valid Data data) {
    public enum Type { CREATE, UPDATE, DEACTIVATE }
    public HrEvent { employeeId = trim(employeeId); }
    public record Data(@NotBlank @Size(max=160) String name, @NotBlank @Email @Size(max=254) String email,
            @Pattern(regexp="\\+?[0-9]{7,15}") String phone, UUID managerId,
            @NotBlank @Size(max=40) String officeCode, @NotNull Boolean active) {
        public Data { name=trim(name); email=trim(email); phone=trim(phone); officeCode=trim(officeCode); }
        public String hash() { return CanonicalHash.of(name,email,phone,managerId,officeCode,active); }
        @Override public String toString() { return "HrData[redacted]"; }
    }
    public String hash() { return CanonicalHash.of(employeeId,version,eventType, eventType==Type.DEACTIVATE ? null : data.hash()); }
    @Override public String toString() { return "HrEvent[redacted]"; }
    private static String trim(String value) { return value == null ? null : value.strip(); }
}
