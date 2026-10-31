package com.moveinsync.roster.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileCacheTest {
    record Value(String safe) { }
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") final ValueOperations<String,String> values=mock(ValueOperations.class);
    final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    ProfileCache cache() { when(redis.opsForValue()).thenReturn(values);return new ProfileCache(redis,new ObjectMapper(),metrics,true,Duration.ofSeconds(60)); }
    @Test void cacheHitUsesTenantAndRevisionAndTracksMetrics() {
        var cache=cache();UUID tenant=UUID.randomUUID();String key=CacheInvalidator.prefix(tenant,"employee","id")+":4";
        when(values.get(key)).thenReturn("{\"safe\":\"cached\"}");
        assertThat(cache.get(tenant,"employee","id","4",Value.class,()->{throw new AssertionError();})).isEqualTo(new Value("cached"));
        assertThat(metrics.get("cache.requests").tags("result","hit","cache","employee").counter().count()).isEqualTo(1);
    }
    @Test void newRevisionCannotReadOldCachedProfile() {
        var cache=cache();UUID tenant=UUID.randomUUID();
        when(values.get(CacheInvalidator.prefix(tenant,"employee","id")+":1")).thenReturn("{\"safe\":\"old\"}");
        assertThat(cache.get(tenant,"employee","id","2",Value.class,()->new Value("new"))).isEqualTo(new Value("new"));
    }
    @Test void redisFailureFallsBackToDatabase() {
        var cache=cache();when(values.get(anyString())).thenThrow(new IllegalStateException("offline"));
        assertThat(cache.get(UUID.randomUUID(),"employee","id","1",Value.class,()->new Value("database"))).isEqualTo(new Value("database"));
        assertThat(metrics.get("cache.failures").counter().count()).isPositive();
    }
    @Test void evictionRemovesPointerAndExactVersion() {
        when(redis.opsForValue()).thenReturn(values);UUID tenant=UUID.randomUUID(),id=UUID.randomUUID();String base=CacheInvalidator.prefix(tenant,"employee",id.toString());
        when(values.get(base)).thenReturn(base+":8");new CacheInvalidator(redis,true).employee(tenant,id);
        verify(redis).delete(base+":8");verify(redis).delete(base);
    }
}
