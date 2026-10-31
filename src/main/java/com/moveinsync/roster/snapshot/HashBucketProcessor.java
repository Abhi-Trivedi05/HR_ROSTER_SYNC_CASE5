package com.moveinsync.roster.snapshot;

import com.moveinsync.roster.shared.CanonicalHash;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class HashBucketProcessor {
    private final JdbcTemplate jdbc;
    public HashBucketProcessor(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public static int bucket(String employee) { return Integer.parseInt(CanonicalHash.of(employee).substring(0,2),16); }
    public long[] diff(UUID tenant,UUID run) {
        long[] counts=new long[3];
        for (int bucket=0;bucket<256;bucket++) {
            var values=jdbc.queryForObject("""
                select count(*) filter(where e.id is null),
                  count(*) filter(where e.id is not null and s.hr_hash is distinct from e.hr_hash and e.source_version<=s.source_version),
                  count(*) filter(where e.id is not null and (s.hr_hash is not distinct from e.hr_hash or e.source_version>s.source_version))
                from snapshot_staging s left join employees e on e.tenant_id=s.tenant_id and e.external_employee_id=s.external_employee_id
                where s.tenant_id=? and s.run_id=? and s.valid and s.bucket=?
                """,(rs,n)->new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3)},tenant,run,bucket);
            for (int i=0;i<3;i++) counts[i]+=values[i];
        }
        return counts;
    }
}
