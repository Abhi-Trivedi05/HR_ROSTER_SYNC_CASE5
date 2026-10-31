package com.moveinsync.roster.snapshot;

import com.moveinsync.roster.IntegrationSupport;
import com.moveinsync.roster.ingestion.HrEvent;
import com.moveinsync.roster.shared.*;
import com.moveinsync.roster.tenant.TenantConfigurationService;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReconciliationIT extends IntegrationSupport {
    @Autowired ReconciliationService snapshots;
    @Autowired TenantConfigurationService configuration;
    final String header="employeeId,version,name,email,phone,managerId,officeCode,active\n";
    String csv(int rows) { StringBuilder result=new StringBuilder(header);for(int n=0;n<rows;n++)result.append("E").append(n).append(",2,Fixture,f@example.invalid,,,BLR,true\n");return result.toString(); }
    void seed(int rows) { for(int n=0;n<rows;n++)submit(event("E"+n,1,HrEvent.Type.CREATE)); }
    SnapshotModels.Report stage(String content,long version) throws Exception {
        authenticate(tenant,"OPERATOR",null);byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var run=snapshots.create(tenant,new SnapshotModels.Create(hash,version,Instant.now()));
        return snapshots.upload(tenant,run.runId(),new ByteArrayInputStream(bytes));
    }
    SnapshotModels.Report approve(SnapshotModels.Report run,boolean override) {
        return snapshots.approve(tenant,run.runId(),new SnapshotModels.Approval(run.checksum(),run.revision(),override),"fixture-operator");
    }
    @Test void normalSnapshotDiffAndApprovalPreserveTransport() throws Exception {
        seed(10);UUID id=employeeId("E0");
        jdbc.update("update employees set pickup_point='Gate',special_assistance=true where tenant_id=? and id=?",tenant,id);
        var run=stage(csv(8),2);
        assertThat(run.status()).isEqualTo("READY");assertThat(run.missingCount()).isEqualTo(2);assertThat(run.updatedCount()).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from employees where tenant_id=? and active",Long.class,tenant)).isEqualTo(10);
        assertThatThrownBy(()->snapshots.apply(tenant,run.runId())).isInstanceOf(ApiException.class);
        approve(run,false);
        assertThat(jdbc.queryForObject("select count(*) from employees where tenant_id=? and active",Long.class,tenant)).isEqualTo(10);
        assertThat(snapshots.apply(tenant,run.runId()).status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from employees where tenant_id=? and active",Long.class,tenant)).isEqualTo(8);
        assertThat(jdbc.queryForMap("select pickup_point,special_assistance from employees where tenant_id=? and id=?",tenant,id))
                .containsEntry("pickup_point","Gate").containsEntry("special_assistance",true);
        long outbox=count("outbox");snapshots.apply(tenant,run.runId());assertThat(count("outbox")).isEqualTo(outbox);
    }
    @Test void fortyPercentMissingIsBlockedUntilExplicitOverride() throws Exception {
        seed(10);var run=stage(csv(6),2);
        assertThat(run.status()).isEqualTo("BLOCKED");assertThat(run.expectedCount()).isEqualTo(10);assertThat(run.incomingCount()).isEqualTo(6);assertThat(run.missingCount()).isEqualTo(4);
        assertThat(run.missingFraction()).isEqualByComparingTo("0.4");
        assertThatThrownBy(()->approve(run,false)).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select count(*) from employees where tenant_id=? and active",Long.class,tenant)).isEqualTo(10);
        assertThat(approve(run,true).status()).isEqualTo("APPROVED");snapshots.apply(tenant,run.runId());
        assertThat(jdbc.queryForObject("select count(*) from employees where tenant_id=? and active",Long.class,tenant)).isEqualTo(6);
    }
    @Test void thresholdChangeRequiresNewReview() throws Exception {
        seed(10);var run=stage(csv(6),2);configuration.update(tenant,new TenantConfigurationService.Update(new BigDecimal("0.5")));
        var review=approve(run,true);assertThat(review.errorCode()).isEqualTo("REVIEW_REQUIRED");assertThat(review.approved()).isFalse();
        assertThat(review.status()).isEqualTo("READY");assertThat(approve(review,false).approved()).isTrue();
    }
    @Test void newerHrEventInvalidatesApprovalAndCannotBeOverwritten() throws Exception {
        seed(2);var run=stage(csv(1),2);approve(run,true);
        authenticate(tenant,"HR_INTEGRATION",null);submit(event("E1",9,HrEvent.Type.UPDATE));authenticate(tenant,"OPERATOR",null);
        assertThat(snapshots.apply(tenant,run.runId()).errorCode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("select source_version from employees where tenant_id=? and external_employee_id='E1'",Long.class,tenant)).isEqualTo(9);
    }
    @Test void emptyDuplicateInvalidAndTruncatedFilesCannotApply() throws Exception {
        assertThat(stage(header,1).status()).isEqualTo("INVALID");
        var duplicate=stage(csv(1)+"E0,2,Fixture,f@example.invalid,,,BLR,true\n",1);
        assertThat(duplicate.status()).isEqualTo("INVALID");assertThat(duplicate.duplicateCount()).isEqualTo(2);
        assertThat(stage(header+"E0,2,Fixture,invalid-email,,,BLR,true\n",1).status()).isEqualTo("INVALID");
        assertThat(stage(header+"E0,2,\"truncated",1).status()).isEqualTo("INVALID");
        assertThat(count("employees")).isZero();assertThat(count("outbox")).isZero();
    }
    @Test void approvalCannotAuthorizeAnotherRunOrChecksum() throws Exception {
        var first=stage(csv(1),1);var second=stage(csv(2),1);
        assertThatThrownBy(()->snapshots.approve(tenant,second.runId(),new SnapshotModels.Approval(first.checksum(),second.revision(),true),"fixture")).isInstanceOf(ApiException.class);
        approve(first,false);assertThatThrownBy(()->snapshots.apply(tenant,second.runId())).isInstanceOf(ApiException.class);
    }
    @Test void stagingCrashLeavesDurableArtifactAndRecoveryRestagesSameRun() throws Exception {
        doThrow(new IllegalStateException("injected crash")).when(failures).at("staging-batch-committed");
        var run=stage(csv(2500),1);assertThat(run.status()).isEqualTo("FAILED");assertThat(count("snapshot_staging")).isEqualTo(1000);
        assertThat(count("employees")).isZero();reset(failures);
        var recovered=snapshots.recover(tenant,run.runId());
        assertThat(recovered.status()).isEqualTo("READY");assertThat(recovered.incomingCount()).isEqualTo(2500);
        assertThat(count("snapshot_staging")).isEqualTo(2500);assertThat(count("employees")).isZero();
    }
    @Test void failedApplyIsAtomicAndRetainsApprovalForRetry() throws Exception {
        var run=stage(csv(5),1);approve(run,false);
        doThrow(new IllegalStateException("injected")).when(failures).at("snapshot-employees-written");
        assertThatThrownBy(()->snapshots.apply(tenant,run.runId())).isInstanceOf(IllegalStateException.class);
        assertThat(count("employees")).isZero();assertThat(count("outbox")).isZero();assertThat(snapshots.report(tenant,run.runId()).status()).isEqualTo("APPROVED");
        reset(failures);assertThat(snapshots.apply(tenant,run.runId()).status()).isEqualTo("COMPLETED");
    }
    @Test void sqlAndJavaCanonicalHashesMatch() {
        var data=new HrEvent.Data("Fixture","f@example.invalid",null,UUID.randomUUID(),"BLR",false);
        String hash=jdbc.queryForObject("select roster_hr_hash(?,?,?,?,?,?)",String.class,data.name(),data.email(),data.phone(),data.managerId(),data.officeCode(),data.active());
        assertThat(hash).isEqualTo(data.hash());
    }
    @Test void snapshotUsesPerEmployeeVersionsWithoutInventingFutureVersions() throws Exception {
        submit(event("HIGH",50,HrEvent.Type.CREATE));submit(event("LOW",1,HrEvent.Type.CREATE));
        var run=stage(header+"HIGH,49,Older,f@example.invalid,,,BLR,true\nLOW,2,Newer,f@example.invalid,,,BLR,true\n",999);
        assertThat(run.status()).isEqualTo("READY");approve(run,false);snapshots.apply(tenant,run.runId());
        assertThat(jdbc.queryForObject("select source_version from employees where tenant_id=? and external_employee_id='HIGH'",Long.class,tenant)).isEqualTo(50);
        assertThat(jdbc.queryForObject("select source_version from employees where tenant_id=? and external_employee_id='LOW'",Long.class,tenant)).isEqualTo(2);
        authenticate(tenant,"HR_INTEGRATION",null);assertThat(submit(event("LOW",3,HrEvent.Type.UPDATE)).status()).isEqualTo(com.moveinsync.roster.ingestion.EventResult.Status.APPLIED);
    }
    @Test void invalidDuplicateIdsAreStillDetectedAndSafeRowErrorsAreReported() throws Exception {
        var run=stage(csv(1)+"E0,2,Fixture,private-invalid-email,,,BLR,true\n",1);
        assertThat(run.duplicateCount()).isEqualTo(2);assertThat(run.invalidCount()).isEqualTo(2);
        var errors=snapshots.errors(tenant,run.runId(),0,100);assertThat(errors).hasSize(2);
        assertThat(mapper.writeValueAsString(errors)).doesNotContain("private-invalid-email","Fixture");
    }
    @Test void conflictingRowVersionIsCountedAsInvalid() throws Exception {
        seed(1);var run=stage(header+"E0,1,Different,f@example.invalid,,,BLR,true\n",99);
        assertThat(run.status()).isEqualTo("INVALID");assertThat(run.invalidCount()).isEqualTo(1);assertThat(run.errorCode()).isEqualTo("VERSION_CONFLICT");
    }
}
