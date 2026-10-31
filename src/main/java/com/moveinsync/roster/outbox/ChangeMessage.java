package com.moveinsync.roster.outbox;

import java.time.Instant;
import java.util.UUID;

/** Broker payload deliberately contains no HR/transport PII. */
public record ChangeMessage(UUID tenantId,UUID eventId,UUID employeeId,String eventType,long sourceVersion,Instant effectiveAt) { }
