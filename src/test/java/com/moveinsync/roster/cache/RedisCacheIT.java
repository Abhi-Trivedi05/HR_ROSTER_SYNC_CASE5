package com.moveinsync.roster.cache;

import com.moveinsync.roster.IntegrationSupport;
import com.moveinsync.roster.employee.*;
import com.moveinsync.roster.ingestion.HrEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import static org.assertj.core.api.Assertions.*;

class RedisCacheIT extends IntegrationSupport {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host",REDIS::getHost);registry.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password",()->"");registry.add("roster.cache.enabled",()->true);
    }
    @Autowired EmployeeService employees;
    @Autowired TransportService transport;
    @Autowired StringRedisTemplate redis;
    @Test void hrUpdateCannotServeOldCachedRevisionEvenBeforeOutboxEviction() {
        submit(event("E",1,HrEvent.Type.CREATE));var id=employeeId("E");authenticate(tenant,"ADMIN",null);
        assertThat(employees.get(tenant,id).name()).isEqualTo("Fixture 1");
        authenticate(tenant,"HR_INTEGRATION",null);submit(event("E",2,HrEvent.Type.UPDATE));authenticate(tenant,"ADMIN",null);
        assertThat(employees.get(tenant,id).name()).isEqualTo("Fixture 2");
    }
    @Test void transportCommitInvalidatesExactPriorVersion() {
        submit(event("E",1,HrEvent.Type.CREATE));var id=employeeId("E");authenticate(tenant,"ADMIN",null);
        var before=employees.get(tenant,id);String base=CacheInvalidator.prefix(tenant,"employee",id.toString());
        assertThat(redis.hasKey(base+":"+before.revision())).isTrue();
        transport.update(tenant,id,new EmployeeDtos.TransportUpdate("Gate",true,0L));
        assertThat(redis.hasKey(base+":"+before.revision())).isFalse();assertThat(employees.get(tenant,id).pickupPoint()).isEqualTo("Gate");
    }
}
