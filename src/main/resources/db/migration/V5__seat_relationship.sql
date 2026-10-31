ALTER TABLE bookings ADD CONSTRAINT uq_booking_cab UNIQUE(tenant_id,id,cab_id);
ALTER TABLE seat_allocations ADD CONSTRAINT fk_seat_booking_cab
 FOREIGN KEY(tenant_id,booking_id,cab_id) REFERENCES bookings(tenant_id,id,cab_id);
