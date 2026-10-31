package com.moveinsync.roster.office;

import com.moveinsync.roster.cache.ProfileCache;
import com.moveinsync.roster.shared.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class OfficeMetadataService {
    public record Metadata(String code,String name,boolean active) { }
    private final JdbcTemplate jdbc; private final ProfileCache cache;
    public OfficeMetadataService(JdbcTemplate jdbc,ProfileCache cache) { this.jdbc=jdbc;this.cache=cache; }
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','HR_INTEGRATION') and @tenantAccess.matches(#tenant)")
    public Metadata get(UUID tenant,String code) {
        var row=jdbc.query("select code,name,active from offices where tenant_id=? and code=?",
                (rs,n)->new Metadata(rs.getString(1),rs.getString(2),rs.getBoolean(3)),tenant,code).stream().findFirst().orElseThrow(ApiException::notFound);
        return cache.get(tenant,"office",code,CanonicalHash.of(row.name(),row.active()),Metadata.class,()->row);
    }
}
