package com.moveinsync.roster.outbox;

import com.moveinsync.roster.*;
import com.moveinsync.roster.cache.CacheInvalidator;
import com.moveinsync.roster.shared.TenantCatalog;
import com.moveinsync.roster.snapshot.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.test.context.*;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties="spring.rabbitmq.dynamic=true")
class ProcessCrashIT extends IntegrationSupport {
    @Container static final RabbitMQContainer RABBIT=new RabbitMQContainer("rabbitmq:4-management-alpine");
    @DynamicPropertySource static void rabbit(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.dynamic",()->true);
        registry.add("spring.rabbitmq.host",RABBIT::getHost);registry.add("spring.rabbitmq.port",RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username",RABBIT::getAdminUsername);registry.add("spring.rabbitmq.password",RABBIT::getAdminPassword);
    }
    @Autowired RabbitChangePublisher brokerPublisher;
    @Autowired org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;
    @Autowired org.springframework.amqp.core.AmqpAdmin admin;
    @Autowired ReconciliationService snapshots;
    @Autowired OutboxRepository outbox;
    @Autowired TenantCatalog tenants;
    @Autowired CacheInvalidator caches;
    @Value("${roster.snapshot.storage-directory}") String storage;
    @Test void killedJvmAfterCommitLeavesOutboxForRestartedPublisher() throws Exception {
        Process child=child("event",null,null);assertThat(child.waitFor(90,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isEqualTo(73);
        assertThat(count("employees")).isEqualTo(1);assertThat(count("outbox")).isEqualTo(1);
        ((org.springframework.amqp.rabbit.core.RabbitAdmin)admin).initialize();
        new OutboxPublisher(outbox,brokerPublisher,tenants,caches,failures,new SimpleMeterRegistry(),true,5).poll();
        var delivered=rabbit.receive("roster.booking",10000);assertThat(delivered).isNotNull();
        assertThat(mapper.readValue(delivered.getBody(),ChangeMessage.class).tenantId()).isEqualTo(tenant);assertThat(outbox.backlog(tenant)).isZero();
    }
    @Test void killedJvmDuringStagingRecoversDurableFileAfterLeaseExpiry() throws Exception {
        Path csv=Files.createTempFile("crash-snapshot-",".csv");
        try {
            try(var writer=Files.newBufferedWriter(csv)) {
                writer.write("employeeId,version,name,email,phone,managerId,officeCode,active\n");
                for(int n=0;n<2500;n++) writer.write("E"+n+",1,Fixture,f@example.invalid,,,BLR,true\n");
            }
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(csv)));
            authenticate(tenant,"OPERATOR",null);var run=snapshots.create(tenant,new SnapshotModels.Create(hash,1L,Instant.now()));
            Process child=child("staging",run.runId(),csv);assertThat(child.waitFor(90,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isEqualTo(74);
            assertThat(count("snapshot_staging")).isEqualTo(1000);assertThat(count("employees")).isZero();
            jdbc.update("update reconciliation_runs set lease_until=now()-interval '1 second' where tenant_id=? and id=?",tenant,run.runId());
            new SnapshotRecovery(jdbc,tenants,snapshots,true).recover();
            assertThat(snapshots.report(tenant,run.runId()).incomingCount()).isEqualTo(2500);assertThat(count("employees")).isZero();
        } finally { Files.deleteIfExists(csv); }
    }
    private Process child(String mode,UUID run,Path csv) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        var builder=new ProcessBuilder(java,"-Xmx256m","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),CrashWorker.class.getName(),mode);
        var env=builder.environment();env.put("SPRING_DATASOURCE_URL",POSTGRES.getJdbcUrl());env.put("DB_PASSWORD",POSTGRES.getPassword());
        env.put("SPRING_DATASOURCE_USERNAME",POSTGRES.getUsername());env.put("CRASH_TENANT",tenant.toString());env.put("CRASH_MODE",mode);
        env.put("SNAPSHOT_STORAGE",storage);if(run!=null)env.put("CRASH_RUN",run.toString());if(csv!=null)env.put("CRASH_CSV",csv.toString());
        builder.redirectErrorStream(true).redirectOutput(Files.createTempFile("crash-worker-",".log").toFile());return builder.start();
    }
}
