package com.moveinsync.roster.ingestion;

import java.util.UUID;

public record EventResult(int index, UUID eventId, Status status, String errorCode) {
    public enum Status { APPLIED, DUPLICATE, STALE, REJECTED, RETRYABLE }
    public static EventResult of(int index, UUID id, Status status, String code) { return new EventResult(index,id,status,code); }
}
