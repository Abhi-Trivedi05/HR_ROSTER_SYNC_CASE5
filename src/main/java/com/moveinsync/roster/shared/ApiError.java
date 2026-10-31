package com.moveinsync.roster.shared;

import java.time.Instant;
import org.slf4j.MDC;

public record ApiError(String errorCode,String message,String correlationId,Instant timestamp) {
    public static ApiError of(String code,String safeMessage) { return new ApiError(code,safeMessage,MDC.get("correlationId"),Instant.now()); }
}
