package com.moveinsync.roster.booking;

import com.moveinsync.roster.shared.*;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class BookingService {
    public record Reserve(@NotNull UUID cabId,@NotNull @Future Instant startsAt,@Min(1) @Max(4) int seats) { }
    private final JdbcTemplate jdbc;private final TenantTransactions transactions;
    public BookingService(JdbcTemplate jdbc,TenantTransactions transactions) { this.jdbc=jdbc;this.transactions=transactions; }
    @PreAuthorize("hasRole('EMPLOYEE') and @tenantAccess.employee(#tenant,#employee)")
    public UUID reserve(UUID tenant,UUID employee,Reserve input) {
        return transactions.inTenant(tenant,true,()-> {
            if (!input.startsAt().isAfter(Instant.now())||input.seats()<1||input.seats()>4) throw ApiException.invalid("Invalid booking request");
            var rows=jdbc.queryForList("select active,source_version from employees where tenant_id=? and id=? for update",tenant,employee);
            if(rows.isEmpty())throw ApiException.notFound();if(!Boolean.TRUE.equals(rows.getFirst().get("active")))throw ApiException.invalid("Employee is inactive");
            if(jdbc.update("update booking_cabs set allocated_seats=allocated_seats+? where tenant_id=? and id=? and allocated_seats+?<=capacity",input.seats(),tenant,input.cabId(),input.seats())!=1)
                throw ApiException.conflict();
            UUID booking=UUID.randomUUID();
            jdbc.update("insert into bookings(tenant_id,id,employee_id,cab_id,starts_at,source_version_at_booking) values (?,?,?,?,?,?)",tenant,booking,employee,input.cabId(),Timestamp.from(input.startsAt()),rows.getFirst().get("source_version"));
            jdbc.update("insert into seat_allocations(tenant_id,booking_id,cab_id,seats) values (?,?,?,?)",tenant,booking,input.cabId(),input.seats());
            return booking;
        });
    }
}
