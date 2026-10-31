package com.moveinsync.roster.outbox;

import com.moveinsync.roster.IntegrationSupport;
import com.moveinsync.roster.cache.CacheInvalidator;
import com.moveinsync.roster.shared.TenantCatalog;
import com.moveinsync.roster.ingestion.HrEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxIT extends IntegrationSupport {
    @Autowired OutboxRepository outbox;
    @Autowired TenantCatalog tenants;
    @Autowired CacheInvalidator cache;
    OutboxPublisher publisher(ChangePublisher broker,int attempts) {
        TenantCatalog scoped=mock(TenantCatalog.class);
        doAnswer(call->{java.util.function.Consumer<java.util.UUID> callback=call.getArgument(0);callback.accept(tenant);return null;}).when(scoped).each(any());
        return new OutboxPublisher(outbox,broker,scoped,cache,failures,new SimpleMeterRegistry(),true,attempts);
    }
    @Test void pendingChangeSurvivesPublisherRestart() {
        submit(event("E",1,HrEvent.Type.CREATE));assertThat(count("outbox")).isEqualTo(1);
        var delivered=new java.util.ArrayList<ChangeMessage>();
        var restarted=publisher(delivered::add,5);restarted.poll();
        assertThat(delivered).hasSize(1);assertThat(jdbc.queryForObject("select status from outbox where tenant_id=?",String.class,tenant)).isEqualTo("PUBLISHED");
    }
    @Test void unavailableBrokerAndTimeoutBackoffThenDeadLetter() {
        submit(event("E",1,HrEvent.Type.CREATE));var publisher=publisher(message->{throw new java.util.concurrent.TimeoutException();},2);
        publisher.poll();assertThat(jdbc.queryForObject("select status from outbox where tenant_id=?",String.class,tenant)).isEqualTo("PENDING");
        jdbc.update("update outbox set available_at=now()-interval '1 second' where tenant_id=?",tenant);publisher.poll();
        assertThat(jdbc.queryForObject("select status from outbox where tenant_id=?",String.class,tenant)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("select attempts from outbox where tenant_id=?",Integer.class,tenant)).isEqualTo(2);
    }
    @Test void crashAfterAcceptanceLeavesLeaseAndRepublishesSameEventId() {
        submit(event("E",1,HrEvent.Type.CREATE));var messages=new java.util.ArrayList<ChangeMessage>();var publisher=publisher(messages::add,5);
        doThrow(new AssertionError("simulated abrupt death")).when(failures).at("broker-accepted-before-mark");
        assertThatThrownBy(publisher::poll).isInstanceOf(AssertionError.class);assertThat(messages).hasSize(1);
        assertThat(jdbc.queryForObject("select status from outbox where tenant_id=?",String.class,tenant)).isEqualTo("CLAIMED");
        reset(failures);jdbc.update("update outbox set lease_until=now()-interval '1 second' where tenant_id=?",tenant);
        publisher(messages::add,5).poll();assertThat(messages).hasSize(2);assertThat(messages.get(0).eventId()).isEqualTo(messages.get(1).eventId());
    }
    @Test void expiredWorkerCannotMarkANewerClaimAsPublished() {
        submit(event("E",1,HrEvent.Type.CREATE));var first=outbox.claim(tenant,1,60).getFirst();
        jdbc.update("update outbox set lease_until=now()-interval '1 second' where tenant_id=?",tenant);
        var second=outbox.claim(tenant,1,60).getFirst();outbox.complete(first);
        assertThat(jdbc.queryForObject("select status from outbox where tenant_id=?",String.class,tenant)).isEqualTo("CLAIMED");
        outbox.complete(second);assertThat(jdbc.queryForObject("select status from outbox where tenant_id=?",String.class,tenant)).isEqualTo("PUBLISHED");
    }
}
