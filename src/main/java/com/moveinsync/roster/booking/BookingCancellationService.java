package com.moveinsync.roster.booking;

import com.moveinsync.roster.outbox.ChangeMessage;
import com.moveinsync.roster.shared.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BookingCancellationService {
    private final JdbcTemplate jdbc;
    private final TenantTransactions transactions;
    private final FailureProbe failures;
    private final MeterRegistry metrics;
    public BookingCancellationService(JdbcTemplate jdbc,TenantTransactions transactions,FailureProbe failures,MeterRegistry metrics) {
        this.jdbc=jdbc;this.transactions=transactions;this.failures=failures;this.metrics=metrics;
    }
    public int consume(ChangeMessage message) {
        if (message==null || message.tenantId()==null || message.eventId()==null || message.employeeId()==null ||
                message.effectiveAt()==null || message.eventType()==null || message.sourceVersion()<0 ||
                !Set.of("EMPLOYEE_DEACTIVATED","EMPLOYEE_UPDATED","EMPLOYEE_TRANSPORT_UPDATED").contains(message.eventType())) {
            throw new IllegalArgumentException("INVALID_MESSAGE");
        }
        int cancelled=transactions.inTenant(message.tenantId(),true,()-> {
            String hash=CanonicalHash.of(message.tenantId(),message.eventId(),message.employeeId(),message.eventType(),message.sourceVersion(),message.effectiveAt());
            var old=jdbc.queryForList("select payload_hash from message_inbox where tenant_id=? and consumer='booking' and event_id=?",
                    String.class,message.tenantId(),message.eventId());
            if (!old.isEmpty()) {
                if (!old.get(0).equals(hash)) throw new IllegalArgumentException("INBOX_PAYLOAD_CONFLICT");
                return 0;
            }
            jdbc.update("insert into message_inbox(tenant_id,consumer,event_id,payload_hash) values (?,'booking',?,?)",message.tenantId(),message.eventId(),hash);
            if (!message.eventType().equals("EMPLOYEE_DEACTIVATED")) return 0;
            // Bounded keyset pages keep cancellation memory bounded even for pathological booking counts.
            UUID cursor=new UUID(0,0); int count=0;
            while (true) {
                var bookings=jdbc.query("select id,cab_id from bookings where tenant_id=? and employee_id=? and status='CONFIRMED' and starts_at>? and source_version_at_booking<=? and id>? order by id limit 500 for update",
                        (rs,n)->new UUID[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)},message.tenantId(),message.employeeId(),Timestamp.from(message.effectiveAt()),message.sourceVersion(),cursor);
                if (bookings.isEmpty()) break;
                for (var booking:bookings) {
                    var seats=jdbc.queryForList("update seat_allocations set released=true where tenant_id=? and booking_id=? and released=false returning seats",Integer.class,message.tenantId(),booking[0]);
                    if (!seats.isEmpty()) {
                        int updated=jdbc.update("update booking_cabs set allocated_seats=allocated_seats-? where tenant_id=? and id=? and allocated_seats>=?",
                                seats.get(0),message.tenantId(),booking[1],seats.get(0));
                        if (updated!=1) throw new IllegalStateException("SEAT_ACCOUNTING_INCONSISTENT");
                    } else if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from seat_allocations where tenant_id=? and booking_id=?)",Boolean.class,message.tenantId(),booking[0]))) {
                        throw new IllegalStateException("SEAT_ALLOCATION_MISSING");
                    }
                    failures.at("seat-released");
                    count+=jdbc.update("update bookings set status='CANCELLED',cancelled_at=now(),cancellation_event_id=? where tenant_id=? and id=? and status='CONFIRMED'",
                            message.eventId(),message.tenantId(),booking[0]);
                }
                cursor=bookings.getLast()[0];
            }
            failures.at("consumer-before-commit");
            return count;
        });
        metrics.counter("booking.cancellations").increment(cancelled);
        return cancelled;
    }
}
