package com.moveinsync.roster;

import com.moveinsync.roster.ingestion.*;
import com.moveinsync.roster.shared.FailureProbe;
import com.moveinsync.roster.snapshot.ReconciliationService;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** Forked JVM fixture; Runtime.halt proves behavior across actual process termination. Never packaged in the app. */
public class CrashWorker {
    public static void main(String[] args) throws Exception {
        var application=new SpringApplicationBuilder(RosterApplication.class,CrashConfig.class).web(WebApplicationType.NONE)
                .properties(Map.of("security.local.enabled","true","roster.cache.enabled","false","roster.outbox.enabled","false",
                        "roster.booking.consumer-enabled","false","roster.snapshot.recovery-enabled","false","spring.rabbitmq.dynamic","false"));
        try(var context=application.run("--security.local.enabled=true","--roster.cache.enabled=false","--roster.outbox.enabled=false",
                "--roster.booking.consumer-enabled=false","--roster.snapshot.recovery-enabled=false","--spring.rabbitmq.dynamic=false","--roster.snapshot.batch-size=1000")) {
            UUID tenant=UUID.fromString(System.getenv("CRASH_TENANT"));
            if (args[0].equals("event")) {
                context.getBean(EventProcessor.class).process(tenant,new HrEvent(UUID.randomUUID(),"DURABLE-CRASH",1L,HrEvent.Type.CREATE,Instant.now(),
                        new HrEvent.Data("Fixture","f@example.invalid",null,null,"BLR",true)),0);
                Runtime.getRuntime().halt(73);
            }
            IntegrationSupport.authenticate(tenant,"OPERATOR",null);
            try(var input=Files.newInputStream(Path.of(System.getenv("CRASH_CSV")))) {
                context.getBean(ReconciliationService.class).upload(tenant,UUID.fromString(System.getenv("CRASH_RUN")),input);
            }
        }
    }
    @TestConfiguration
    static class CrashConfig {
        @Bean @Primary FailureProbe abruptFailure() {
            return point->{ if (System.getenv("CRASH_MODE").equals("staging")&&point.equals("staging-batch-committed")) Runtime.getRuntime().halt(74); };
        }
    }
}
