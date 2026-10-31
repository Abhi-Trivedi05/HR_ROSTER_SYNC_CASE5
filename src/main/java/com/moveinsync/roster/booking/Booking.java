package com.moveinsync.roster.booking;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.*;

@Entity @Table(name="bookings") @IdClass(Booking.Key.class)
public class Booking {
    @Id private UUID tenantId;
    @Id private UUID id;
    @Column(nullable=false) private UUID employeeId;
    @Column(nullable=false) private UUID cabId;
    @Column(nullable=false) private Instant startsAt;
    @Column(nullable=false,length=16) private String status;
    private Instant cancelledAt;
    private UUID cancellationEventId;
    @Column(nullable=false) private Instant createdAt;
    @Column(nullable=false) private long sourceVersionAtBooking;
    protected Booking() { }
    public static class Key implements Serializable {
        private UUID tenantId; private UUID id;
        public Key() { }
        @Override public boolean equals(Object other) { return other instanceof Key k && Objects.equals(tenantId,k.tenantId)&&Objects.equals(id,k.id); }
        @Override public int hashCode() { return Objects.hash(tenantId,id); }
    }
}
