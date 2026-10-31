package com.moveinsync.roster;

import com.moveinsync.roster.ingestion.*;
import com.moveinsync.roster.shared.FailureProbe;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties={"security.local.enabled=true","roster.cache.enabled=false","roster.outbox.enabled=false",
        "roster.booking.consumer-enabled=false","roster.snapshot.recovery-enabled=false","spring.rabbitmq.dynamic=false","roster.snapshot.batch-size=1000"})
@AutoConfigureMockMvc @Testcontainers @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class IntegrationSupport {
    public static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    static final String STORAGE=System.getProperty("roster.it.storage",System.getProperty("java.io.tmpdir")+"/roster-it-"+UUID.randomUUID());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        // Explicit opt-in for a real externally supplied PostgreSQL. Never silently fall back when Docker fails.
        String external=System.getProperty("roster.it.jdbcUrl");
        if(external==null) {
            POSTGRES.start();registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username",POSTGRES::getUsername);registry.add("spring.datasource.password",POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url",()->external);
            registry.add("spring.datasource.username",()->System.getProperty("roster.it.username","postgres"));
            registry.add("spring.datasource.password",()->System.getProperty("roster.it.password",""));
        }
        registry.add("roster.snapshot.storage-directory",()->STORAGE);
    }
    @org.junit.jupiter.api.AfterAll static void stopDatabase() { if(System.getProperty("roster.it.jdbcUrl")==null&&POSTGRES.isRunning())POSTGRES.stop(); }
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected EventProcessor processor;
    @Autowired protected BatchProcessingService batch;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected MockMvc mvc;
    @MockitoBean(name="failureProbe") protected FailureProbe failures;
    protected UUID tenant;
    @BeforeEach void seedTenant() {
        tenant=UUID.randomUUID();
        jdbc.update("insert into tenants(id,name) values (?,'Fixture tenant')",tenant);
        jdbc.update("insert into offices(tenant_id,code,name) values (?,'BLR','Fixture office')",tenant);
        authenticate(tenant,"HR_INTEGRATION",null);
    }
    public static void authenticate(UUID tenant,String role,UUID employee) {
        var builder=Jwt.withTokenValue("fixture").header("alg","RS256").subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now().minusSeconds(1)).expiresAt(Instant.now().plusSeconds(3600)).claim("tenant_id",tenant.toString())
                .claim("roles",List.of(role));
        if (employee!=null) builder.claim("employee_id",employee.toString());
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(builder.build(),List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }
    protected HrEvent event(String employee,long version,HrEvent.Type type) {
        return new HrEvent(UUID.randomUUID(),employee,version,type,Instant.now().minusSeconds(10),
                type==HrEvent.Type.DEACTIVATE?null:new HrEvent.Data("Fixture "+version,"fixture@example.invalid","+919876543210",null,"BLR",true));
    }
    protected EventResult submit(HrEvent event) { return batch.process(tenant,List.of(mapper.valueToTree(event))).getFirst(); }
    protected UUID employeeId(String external) { return jdbc.queryForObject("select id from employees where tenant_id=? and external_employee_id=?",UUID.class,tenant,external); }
    protected long count(String table) {
        if (!Set.of("employees","outbox","processed_events","event_receipts","message_inbox","reconciliation_audit","snapshot_staging").contains(table)) throw new IllegalArgumentException();
        return jdbc.queryForObject("select count(*) from "+table+" where tenant_id=?",Long.class,tenant);
    }
}
