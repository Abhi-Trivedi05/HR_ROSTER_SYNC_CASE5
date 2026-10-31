package com.moveinsync.roster.snapshot;

import com.moveinsync.roster.shared.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationService {
    private final JdbcTemplate jdbc;
    private final TenantTransactions transactions;
    private final CsvStreamingReader csv;
    private final SnapshotStagingRepository staging;
    private final ReconciliationDiffService diff;
    private final ReconciliationReportService reports;
    private final SafetyBrakeService safety;
    private final SnapshotApplier applier;
    private final FailureProbe failures;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final Path storage;
    private final long maxBytes;
    public ReconciliationService(JdbcTemplate jdbc,TenantTransactions transactions,CsvStreamingReader csv,
            SnapshotStagingRepository staging,ReconciliationDiffService diff,ReconciliationReportService reports,
            SafetyBrakeService safety,SnapshotApplier applier,FailureProbe failures,MeterRegistry metrics,Clock clock,
            @Value("${roster.snapshot.storage-directory:./var/snapshots}") String storage,
            @Value("${roster.snapshot.max-bytes:134217728}") long maxBytes) {
        this.jdbc=jdbc;this.transactions=transactions;this.csv=csv;this.staging=staging;this.diff=diff;this.reports=reports;
        this.safety=safety;this.applier=applier;this.failures=failures;this.metrics=metrics;this.clock=clock;
        this.storage=Path.of(storage).toAbsolutePath().normalize();this.maxBytes=maxBytes;
    }
    @PreAuthorize("hasRole('OPERATOR') and @tenantAccess.matches(#tenant)")
    public SnapshotModels.Report create(UUID tenant,SnapshotModels.Create input) {
        if (input.sourceCutoff().isAfter(clock.instant())) throw ApiException.invalid("Source cutoff cannot be in the future");
        return transactions.inTenant(tenant,true,()-> {
            UUID id=UUID.randomUUID();
            jdbc.update("insert into reconciliation_runs(tenant_id,id,status,expected_checksum,source_version,source_cutoff) values (?,?,'STAGING',?,?,?)",
                    tenant,id,input.checksum(),input.sourceVersion(),Timestamp.from(input.sourceCutoff()));
            return reports.report(tenant,id);
        });
    }
    @PreAuthorize("hasRole('OPERATOR') and @tenantAccess.matches(#tenant)")
    public SnapshotModels.Report report(UUID tenant,UUID run) { return transactions.inTenant(tenant,false,()->reports.report(tenant,run)); }
    @PreAuthorize("hasRole('OPERATOR') and @tenantAccess.matches(#tenant)")
    public java.util.List<ReconciliationReportService.RowError> errors(UUID tenant,UUID run,long after,int limit) {
        return transactions.inTenant(tenant,false,()->{reports.report(tenant,run);return reports.errors(tenant,run,after,limit);});
    }
    @PreAuthorize("hasRole('OPERATOR') and @tenantAccess.matches(#tenant)")
    public SnapshotModels.Report upload(UUID tenant,UUID run,InputStream input) {
        UUID token=claim(tenant,run,false);
        long started=System.nanoTime(); Path temporary=null;
        try {
            Files.createDirectories(storage);
            temporary=Files.createTempFile(storage,tenant+"-"+run+"-",".part");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            long total=0,heartbeat=0; byte[] buffer=new byte[65536];
            try (var output=Files.newOutputStream(temporary)) {
                int read;
                while ((read=input.read(buffer))!=-1) {
                    total+=read; if (total>maxBytes) throw new InvalidFile("FILE_LIMIT_EXCEEDED");
                    digest.update(buffer,0,read);output.write(buffer,0,read);
                    if (total-heartbeat>=1048576) { heartbeat=total; beat(tenant,run,token); }
                }
            }
            String checksum=HexFormat.of().formatHex(digest.digest());
            String expected=transactions.inTenant(tenant,false,()->jdbc.queryForObject("select expected_checksum from reconciliation_runs where tenant_id=? and id=?",String.class,tenant,run));
            if (!checksum.equals(expected)) throw new InvalidFile("CHECKSUM_MISMATCH");
            beat(tenant,run,token);
            // Immutable run/checksum: atomic move cannot change the meaning of an existing approval.
            try { Files.move(temporary,path(tenant,run),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary,path(tenant,run),StandardCopyOption.REPLACE_EXISTING); }
            return stage(tenant,run,token,checksum);
        } catch (InvalidFile ex) { return failed(tenant,run,token,"INVALID",ex.code); }
        catch (IOException ex) { return failed(tenant,run,token,"FAILED","UPLOAD_IO_FAILURE"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("Hash unavailable"); }
        catch (RuntimeException ex) { return failed(tenant,run,token,"FAILED","RETRYABLE_STAGING_FAILURE"); }
        finally {
            if (temporary!=null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            metrics.timer("reconciliation.duration","phase","staging").record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
    // Invoked by the trusted recovery scheduler, never exposed as an unguarded HTTP operation.
    SnapshotModels.Report recover(UUID tenant,UUID run) {
        UUID token=claim(tenant,run,true);
        try {
            String expected=transactions.inTenant(tenant,false,()->jdbc.queryForObject("select expected_checksum from reconciliation_runs where tenant_id=? and id=?",String.class,tenant,run));
            // Recheck durable artifact integrity on every restart, using constant memory.
            var digest=MessageDigest.getInstance("SHA-256");
            try (InputStream source=Files.newInputStream(path(tenant,run));var input=new DigestInputStream(source,digest)) { input.transferTo(OutputStream.nullOutputStream()); }
            if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) return failed(tenant,run,token,"INVALID","CHECKSUM_MISMATCH");
            return stage(tenant,run,token,expected);
        } catch (Exception ex) { return failed(tenant,run,token,"FAILED","RETRYABLE_STAGING_FAILURE"); }
    }
    boolean artifactExists(UUID tenant,UUID run) { return Files.isRegularFile(path(tenant,run)); }
    private SnapshotModels.Report stage(UUID tenant,UUID run,UUID token,String checksum) throws IOException {
        transactions.inTenant(tenant,false,()-> {
            staging.heartbeat(tenant,run,token);
            jdbc.update("delete from snapshot_staging where tenant_id=? and run_id=?",tenant,run);
            return null;
        });
        try (InputStream input=Files.newInputStream(path(tenant,run))) {
            csv.read(input,batch->{ staging.insert(tenant,run,token,batch);failures.at("staging-batch-committed"); });
        } catch (IOException ex) { return failed(tenant,run,token,"INVALID","MALFORMED_CSV"); }
        return transactions.inTenant(tenant,true,()-> {
            staging.heartbeat(tenant,run,token);
            staging.duplicates(tenant,run);
            jdbc.update("update reconciliation_runs set checksum=?,lease_until=null,staging_token=null where tenant_id=? and id=? and staging_token=?",checksum,tenant,run,token);
            return diff.refresh(tenant,run);
        });
    }
    private UUID claim(UUID tenant,UUID run,boolean recovery) {
        return transactions.inTenant(tenant,true,()-> {
            var report=reports.report(tenant,run);
            if (!Set.of("STAGING","FAILED").contains(report.status())) throw ApiException.conflict();
            UUID token=UUID.randomUUID();
            int changed=jdbc.update("update reconciliation_runs set status='STAGING',staging_token=?,lease_until=now()+interval '2 minutes',revision=revision+1,recovery_attempts=recovery_attempts+?,error_code=null where tenant_id=? and id=? and (lease_until is null or lease_until<now())",
                    token,recovery?1:0,tenant,run);
            if (changed!=1) throw ApiException.conflict();
            return token;
        });
    }
    private void beat(UUID tenant,UUID run,UUID token) { transactions.inTenant(tenant,false,()->{staging.heartbeat(tenant,run,token);return null;}); }
    private SnapshotModels.Report failed(UUID tenant,UUID run,UUID token,String status,String code) {
        return transactions.inTenant(tenant,false,()-> {
            jdbc.update("update reconciliation_runs set status=?,error_code=?,lease_until=null,staging_token=null,revision=revision+1,updated_at=now() where tenant_id=? and id=? and staging_token=?",status,code,tenant,run,token);
            return reports.report(tenant,run);
        });
    }
    private Path path(UUID tenant,UUID run) { return storage.resolve(tenant+"-"+run+".csv"); }
    @PreAuthorize("hasRole('OPERATOR') and @tenantAccess.matches(#tenant)")
    public SnapshotModels.Report refresh(UUID tenant,UUID run) {
        return transactions.inTenant(tenant,true,()-> {
            if (!Set.of("READY","BLOCKED","APPROVED").contains(reports.report(tenant,run).status())) throw ApiException.conflict();
            return diff.refresh(tenant,run);
        });
    }
    @PreAuthorize("hasRole('OPERATOR') and @tenantAccess.matches(#tenant)")
    public SnapshotModels.Report approve(UUID tenant,UUID run,SnapshotModels.Approval approval,String actor) {
        return transactions.inTenant(tenant,true,()-> {
            var report=reports.report(tenant,run);
            if (!Set.of("READY","BLOCKED").contains(report.status()) || report.revision()!=approval.expectedRevision()
                    || !Objects.equals(report.checksum(),approval.checksum())) throw ApiException.conflict();
            if (reviewChanged(tenant,run,report)) return changed(tenant,run);
            if (report.status().equals("BLOCKED")&&!approval.overrideSafetyBrake()) throw ApiException.invalid("Explicit safety-brake override required");
            jdbc.update("update reconciliation_runs set status='APPROVED',approved_checksum=checksum,approved_at=now(),approved_by=?,override_brake=?,revision=revision+1,updated_at=now() where tenant_id=? and id=?",
                    actor,approval.overrideSafetyBrake(),tenant,run);
            return reports.report(tenant,run);
        });
    }
    @PreAuthorize("hasRole('OPERATOR') and @tenantAccess.matches(#tenant)")
    public SnapshotModels.Report apply(UUID tenant,UUID run) {
        long started=System.nanoTime();
        try {
            return transactions.inTenant(tenant,true,()-> {
                var report=reports.report(tenant,run);
                if (report.status().equals("COMPLETED")) return report;
                if (!report.status().equals("APPROVED")||!report.approved()) throw ApiException.conflict();
                if (reviewChanged(tenant,run,report)) return changed(tenant,run);
                int approved=jdbc.queryForObject("select count(*) from reconciliation_runs where tenant_id=? and id=? and checksum=approved_checksum and checksum=expected_checksum",Integer.class,tenant,run);
                if (approved!=1) throw ApiException.conflict();
                applier.apply(tenant,run,report);
                return reports.report(tenant,run);
            });
        } finally { metrics.timer("reconciliation.duration","phase","apply").record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS); }
    }
    private boolean reviewChanged(UUID tenant,UUID run,SnapshotModels.Report report) {
        var policy=safety.policy(tenant);
        return Boolean.TRUE.equals(jdbc.queryForObject("select r.roster_revision is distinct from t.hr_revision or r.config_revision is distinct from t.config_revision from reconciliation_runs r join tenants t on t.id=r.tenant_id where r.tenant_id=? and r.id=?",Boolean.class,tenant,run))
                || report.threshold().compareTo(policy.threshold())!=0;
    }
    private SnapshotModels.Report changed(UUID tenant,UUID run) {
        diff.refresh(tenant,run);
        jdbc.update("update reconciliation_runs set error_code='REVIEW_REQUIRED' where tenant_id=? and id=?",tenant,run);
        return reports.report(tenant,run);
    }
    private static class InvalidFile extends IOException {
        final String code;
        InvalidFile(String code) { super(code);this.code=code; }
    }
}
