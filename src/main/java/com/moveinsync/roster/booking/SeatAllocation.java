package com.moveinsync.roster.booking;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.*;

@Entity @Table(name="seat_allocations") @IdClass(SeatAllocation.Key.class)
public class SeatAllocation {
    @Id private UUID tenantId;
    @Id private UUID bookingId;
    @Column(nullable=false) private UUID cabId;
    @Column(nullable=false) private int seats;
    @Column(nullable=false) private boolean released;
    protected SeatAllocation() { }
    public static class Key implements Serializable {
        private UUID tenantId; private UUID bookingId;
        public Key() { }
        @Override public boolean equals(Object other) { return other instanceof Key k && Objects.equals(tenantId,k.tenantId)&&Objects.equals(bookingId,k.bookingId); }
        @Override public int hashCode() { return Objects.hash(tenantId,bookingId); }
    }
}
