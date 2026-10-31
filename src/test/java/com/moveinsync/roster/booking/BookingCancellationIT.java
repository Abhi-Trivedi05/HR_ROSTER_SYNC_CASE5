package com.moveinsync.roster.booking;

import com.moveinsync.roster.IntegrationSupport;
import com.moveinsync.roster.ingestion.HrEvent;
import com.moveinsync.roster.outbox.ChangeMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingCancellationIT extends IntegrationSupport {
    @Autowired BookingCancellationService cancellations;
    UUID seedBookings(int count) {
        submit(event("E",1,HrEvent.Type.CREATE));UUID employee=employeeId("E"),cab=UUID.randomUUID();
        jdbc.update("insert into booking_cabs(tenant_id,id,capacity,allocated_seats) values (?,?,100,?)",tenant,cab,count);
        for(int n=0;n<count;n++) {
            UUID id=UUID.randomUUID();jdbc.update("insert into bookings(tenant_id,id,employee_id,cab_id,starts_at) values (?,?,?,?,?)",tenant,id,employee,cab,Timestamp.from(Instant.now().plusSeconds(86400)));
            jdbc.update("insert into seat_allocations(tenant_id,booking_id,cab_id,seats) values (?,?,?,1)",tenant,id,cab);
        }
        return employee;
    }
    ChangeMessage message(UUID employee) { return new ChangeMessage(tenant,UUID.randomUUID(),employee,"EMPLOYEE_DEACTIVATED",2,Instant.now()); }
    @Test void multipleFutureBookingsCancelledAndSeatsReleasedOnce() {
        UUID employee=seedBookings(3);var message=message(employee);
        assertThat(cancellations.consume(message)).isEqualTo(3);assertThat(cancellations.consume(message)).isZero();
        assertThat(jdbc.queryForObject("select allocated_seats from booking_cabs where tenant_id=?",Integer.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from bookings where tenant_id=? and status='CANCELLED'",Integer.class,tenant)).isEqualTo(3);
        assertThat(count("message_inbox")).isEqualTo(1);
        assertThat(cancellations.consume(message(employee))).isZero();
    }
    @Test void noBookingsAndAlreadyCancelledBookingsAreSafe() {
        UUID employee=seedBookings(0);assertThat(cancellations.consume(message(employee))).isZero();
        assertThat(cancellations.consume(message(employee))).isZero();
    }
    @Test void failureDuringSeatReleaseRollsBackAndRedeliverySucceeds() {
        UUID employee=seedBookings(2);var message=message(employee);
        doThrow(new IllegalStateException("injected")).when(failures).at("seat-released");
        assertThatThrownBy(()->cancellations.consume(message)).isInstanceOf(IllegalStateException.class);
        assertThat(count("message_inbox")).isZero();
        assertThat(jdbc.queryForObject("select allocated_seats from booking_cabs where tenant_id=?",Integer.class,tenant)).isEqualTo(2);
        reset(failures);assertThat(cancellations.consume(message)).isEqualTo(2);
    }
    @Test void consumerCrashBeforeCommitAndRedeliveryReleaseSeatsOnce() {
        UUID employee=seedBookings(2);var message=message(employee);
        doThrow(new IllegalStateException("injected")).when(failures).at("consumer-before-commit");
        assertThatThrownBy(()->cancellations.consume(message)).isInstanceOf(IllegalStateException.class);reset(failures);
        assertThat(cancellations.consume(message)).isEqualTo(2);assertThat(cancellations.consume(message)).isZero();
    }
    @Test void employeeExitRemainsCommittedWhenConsumerFails() {
        UUID employee=seedBookings(1);submit(event("E",2,HrEvent.Type.DEACTIVATE));
        doThrow(new IllegalStateException("downstream unavailable")).when(failures).at("seat-released");
        assertThatThrownBy(()->cancellations.consume(message(employee))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select active from employees where tenant_id=? and id=?",Boolean.class,tenant,employee)).isFalse();
        assertThat(count("outbox")).isEqualTo(2);
    }
    @Test void delayedExitCannotCancelNewRehireBookings() {
        UUID employee=seedBookings(1);var exit=message(employee);
        jdbc.update("update bookings set source_version_at_booking=9 where tenant_id=? and employee_id=?",tenant,employee);
        assertThat(cancellations.consume(exit)).isZero();
        assertThat(jdbc.queryForObject("select allocated_seats from booking_cabs where tenant_id=?",Integer.class,tenant)).isEqualTo(1);
    }
}
