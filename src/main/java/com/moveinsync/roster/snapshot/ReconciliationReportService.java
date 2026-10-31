package com.moveinsync.roster.snapshot;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.moveinsync.roster.shared.ApiException;

@Service
public class ReconciliationReportService {
    public record RowError(long rowNumber,String errorCode) { }
    private final JdbcTemplate jdbc;
    public ReconciliationReportService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public SnapshotModels.Report report(UUID tenant,UUID run) {
        return jdbc.query("select * from reconciliation_runs where tenant_id=? and id=?",(rs,n)->new SnapshotModels.Report(
                rs.getObject("id",UUID.class),rs.getString("status"),rs.getString("checksum"),rs.getLong("source_version"),
                rs.getTimestamp("source_cutoff").toInstant(),rs.getLong("revision"),rs.getLong("expected_count"),rs.getLong("incoming_count"),
                rs.getLong("missing_count"),rs.getLong("invalid_count"),rs.getLong("duplicate_count"),rs.getLong("new_count"),
                rs.getLong("updated_count"),rs.getLong("unchanged_count"),rs.getBigDecimal("missing_fraction"),rs.getBigDecimal("threshold"),
                rs.getString("error_code"),rs.getTimestamp("approved_at")!=null,rs.getBoolean("override_brake")),tenant,run).stream().findFirst().orElseThrow(ApiException::notFound);
    }
    public java.util.List<RowError> errors(UUID tenant,UUID run,long after,int limit) {
        if(after<0||limit<1||limit>200)throw ApiException.invalid("Invalid error-page bounds");
        return jdbc.query("select row_number,error_code from snapshot_staging where tenant_id=? and run_id=? and not valid and row_number>? order by row_number limit ?",
                (rs,n)->new RowError(rs.getLong(1),rs.getString(2)),tenant,run,after,limit);
    }
}
