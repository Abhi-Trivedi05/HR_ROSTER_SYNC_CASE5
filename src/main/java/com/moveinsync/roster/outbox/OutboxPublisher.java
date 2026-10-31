package com.moveinsync.roster.outbox;

import com.moveinsync.roster.cache.CacheInvalidator;
import com.moveinsync.roster.shared.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {
    private final OutboxRepository outbox;
    private final ChangePublisher publisher;
    private final TenantCatalog tenants;
    private final CacheInvalidator caches;
    private final FailureProbe failures;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final int maxAttempts;
    private final AtomicLong backlog=new AtomicLong();
    public OutboxPublisher(OutboxRepository outbox,ChangePublisher publisher,TenantCatalog tenants,CacheInvalidator caches,
            FailureProbe failures,MeterRegistry metrics,@Value("${roster.outbox.enabled:true}") boolean enabled,
            @Value("${roster.outbox.max-attempts:10}") int maxAttempts) {
        this.outbox=outbox;this.publisher=publisher;this.tenants=tenants;this.caches=caches;this.failures=failures;
        this.metrics=metrics;this.enabled=enabled;this.maxAttempts=maxAttempts;
        metrics.gauge("outbox.backlog",backlog);
    }
    @Scheduled(fixedDelayString="${roster.outbox.poll-ms:1000}")
    public void poll() {
        if (!enabled) return;
        AtomicLong total=new AtomicLong();
        try {
            tenants.each(tenant->{
                outbox.expireExhausted(tenant,maxAttempts);
                for (var claimed:outbox.claim(tenant,10,60)) deliver(claimed);
                total.addAndGet(outbox.backlog(tenant));
            });
            backlog.set(total.get());
        } catch (RuntimeException ex) { metrics.counter("outbox.poll.failures").increment(); }
    }
    public void deliver(OutboxRepository.Claimed claimed) {
        try {
            caches.employee(claimed.message().tenantId(),claimed.message().employeeId());
            publisher.publish(claimed.message());
            failures.at("broker-accepted-before-mark");
            outbox.complete(claimed);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            metrics.counter("outbox.publish.failures").increment();
            outbox.fail(claimed,maxAttempts,"PUBLISH_FAILED");
        }
    }
}
