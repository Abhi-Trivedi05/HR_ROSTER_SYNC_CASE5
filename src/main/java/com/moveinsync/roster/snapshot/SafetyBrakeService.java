package com.moveinsync.roster.snapshot;

import com.moveinsync.roster.cache.ProfileCache;
import java.math.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SafetyBrakeService {
    public record Policy(BigDecimal threshold,long revision) { }
    public record Decision(BigDecimal fraction,boolean blocked) { }
    private final BigDecimal defaultThreshold;
    private final JdbcTemplate jdbc;
    private final ProfileCache cache;
    public SafetyBrakeService(@Value("${roster.snapshot.missing-threshold:0.30}") BigDecimal threshold,JdbcTemplate jdbc,ProfileCache cache) {
        if (threshold.signum()<=0 || threshold.compareTo(BigDecimal.ONE)>0 || threshold.stripTrailingZeros().scale()>5) throw new IllegalArgumentException("Missing threshold must be in (0,1] with at most five decimal places");
        this.defaultThreshold=threshold;this.jdbc=jdbc;this.cache=cache;
    }
    public Policy policy(UUID tenant) {
        var row=jdbc.queryForObject("select missing_threshold,config_revision from tenants where id=?",
                (rs,n)->new Policy(rs.getBigDecimal(1)==null?defaultThreshold:rs.getBigDecimal(1),rs.getLong(2)),tenant);
        return cache.get(tenant,"config","current",row.revision()+":"+row.threshold(),Policy.class,()->row);
    }
    public static Decision decide(long expected,long missing,BigDecimal threshold) {
        BigDecimal fraction=expected==0?BigDecimal.ZERO:BigDecimal.valueOf(missing).divide(BigDecimal.valueOf(expected),10,RoundingMode.HALF_UP);
        // Compare exact integer products for the brake, not the rounded report fraction.
        boolean blocked=expected>0 && BigDecimal.valueOf(missing).compareTo(threshold.multiply(BigDecimal.valueOf(expected)))>=0;
        return new Decision(fraction,blocked);
    }
}
