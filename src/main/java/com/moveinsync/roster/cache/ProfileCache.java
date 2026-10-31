package com.moveinsync.roster.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProfileCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final Duration ttl;
    private final java.util.concurrent.atomic.AtomicLong hits=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong requests=new java.util.concurrent.atomic.AtomicLong();
    public ProfileCache(StringRedisTemplate redis,ObjectMapper mapper,MeterRegistry metrics,
            @Value("${roster.cache.enabled:true}") boolean enabled,@Value("${roster.cache.ttl:PT60S}") Duration ttl) {
        this.redis=redis;this.mapper=mapper;this.metrics=metrics;this.enabled=enabled;this.ttl=ttl;
        metrics.gauge("cache.hit.ratio",this,c->c.requests.get()==0?0d:(double)c.hits.get()/c.requests.get());
    }
    public <T> T get(UUID tenant,String kind,String id,String revision,Class<T> type,Supplier<T> load) {
        requests.incrementAndGet();
        String key=CacheInvalidator.prefix(tenant,kind,id)+":"+revision;
        if (enabled) {
            try {
                String value=redis.opsForValue().get(key);
                if (value!=null) { T parsed=mapper.readValue(value,type);hits.incrementAndGet();metrics.counter("cache.requests","result","hit","cache",kind).increment();return parsed; }
            } catch (Exception ignored) { metrics.counter("cache.failures").increment(); }
        }
        metrics.counter("cache.requests","result","miss","cache",kind).increment();
        T value=load.get();
        if (enabled && value!=null) {
            try { redis.opsForValue().set(key,mapper.writeValueAsString(value),ttl); redis.opsForValue().set(CacheInvalidator.prefix(tenant,kind,id),key,ttl); }
            catch (Exception ignored) { metrics.counter("cache.failures").increment(); }
        }
        return value;
    }
}
