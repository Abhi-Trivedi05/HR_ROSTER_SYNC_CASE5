package com.moveinsync.roster.cache;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class CacheInvalidator {
    private final StringRedisTemplate redis;
    private final boolean enabled;
    public CacheInvalidator(StringRedisTemplate redis,@Value("${roster.cache.enabled:true}") boolean enabled) { this.redis=redis;this.enabled=enabled; }
    public static String prefix(UUID tenant,String type,String key) { return "roster:"+tenant+":"+type+":"+key; }
    public void employee(UUID tenant,UUID employee) { evict(prefix(tenant,"employee",employee.toString())); }
    public void office(UUID tenant,String code) { evict(prefix(tenant,"office",code)); }
    public void config(UUID tenant) { evict(prefix(tenant,"config","current")); }
    private void evict(String key) {
        if (!enabled) return;
        try { String versionKey=redis.opsForValue().get(key); if (versionKey!=null) redis.delete(versionKey);redis.delete(key); }
        catch (RuntimeException ignored) { /* versioned cache keys prevent stale reads even if eviction fails */ }
    }
}
