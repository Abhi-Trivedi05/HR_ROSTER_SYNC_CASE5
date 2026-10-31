package com.moveinsync.roster.snapshot;

import com.moveinsync.roster.*;
import com.moveinsync.roster.shared.TenantCatalog;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import static org.assertj.core.api.Assertions.*;

class ProcessStagingCrashIT extends IntegrationSupport {
    @Autowired ReconciliationService snapshots;
    @Autowired TenantCatalog tenants;
    @Value("${roster.snapshot.storage-directory}") String storage;
    @Test void realProcessCrashAfterCommitLeavesPendingOutbox() throws Exception {
        Process child=child("event",null,null);assertThat(child.waitFor(90,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isEqualTo(73);
        assertThat(count("employees")).isEqualTo(1);assertThat(count("outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from outbox where tenant_id=?",String.class,tenant)).isEqualTo("PENDING");
    }
    @Test void realProcessCrashDuringStagingRecoversWithoutPrematureEmployeeChanges() throws Exception {
        Path csv=Files.createTempFile("staging-process-crash-",".csv");
        try {
            try(var writer=Files.newBufferedWriter(csv)) {
                writer.write("employeeId,version,name,email,phone,managerId,officeCode,active\n");
                for(int n=0;n<2500;n++)writer.write("E"+n+",1,Fixture,f@example.invalid,,,BLR,true\n");
            }
            String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(csv)));
            authenticate(tenant,"OPERATOR",null);var run=snapshots.create(tenant,new SnapshotModels.Create(checksum,1L,Instant.now()));
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
        var env=builder.environment();env.put("SPRING_DATASOURCE_URL",System.getProperty("roster.it.jdbcUrl",POSTGRES.isRunning()?POSTGRES.getJdbcUrl():""));
        env.put("SPRING_DATASOURCE_USERNAME",System.getProperty("roster.it.username",POSTGRES.isRunning()?POSTGRES.getUsername():"postgres"));
        env.put("DB_PASSWORD",System.getProperty("roster.it.password",POSTGRES.isRunning()?POSTGRES.getPassword():""));
        env.put("CRASH_TENANT",tenant.toString());env.put("CRASH_MODE",mode);env.put("SNAPSHOT_STORAGE",storage);
        if(run!=null)env.put("CRASH_RUN",run.toString());if(csv!=null)env.put("CRASH_CSV",csv.toString());
        builder.redirectErrorStream(true).redirectOutput(Files.createTempFile("staging-worker-",".log").toFile());return builder.start();
    }
}
