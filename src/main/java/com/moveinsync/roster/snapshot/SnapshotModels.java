package com.moveinsync.roster.snapshot;

import com.moveinsync.roster.ingestion.HrEvent;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class SnapshotModels {
    private SnapshotModels() { }
    public record Create(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String checksum,
            @NotNull @PositiveOrZero Long sourceVersion,@NotNull Instant sourceCutoff) { }
    public record Approval(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String checksum,
            @NotNull @PositiveOrZero Long expectedRevision,boolean overrideSafetyBrake) { }
    public record Row(long number,String employeeId,Long version,HrEvent.Data data,boolean valid,String errorCode,String hash,int bucket) {
        @Override public String toString() { return "SnapshotRow[redacted]"; }
    }
    public record Report(UUID runId,String status,String checksum,long sourceVersion,Instant sourceCutoff,long revision,
            long expectedCount,long incomingCount,long missingCount,long invalidCount,long duplicateCount,
            long newCount,long updatedCount,long unchangedCount,BigDecimal missingFraction,BigDecimal threshold,
            String errorCode,boolean approved,boolean overrideSafetyBrake) { }
}
