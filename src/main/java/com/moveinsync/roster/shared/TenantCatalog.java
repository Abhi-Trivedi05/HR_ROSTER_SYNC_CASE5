package com.moveinsync.roster.shared;

import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Background control-plane enumeration: keyset pages only, never employee data across tenants. */
@Component
public class TenantCatalog {
    private final JdbcTemplate jdbc;
    public TenantCatalog(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void each(Consumer<UUID> action) {
        UUID cursor=null;
        while (true) {
            var page=cursor==null?jdbc.queryForList("select id from tenants order by id limit 100",UUID.class):jdbc.queryForList("select id from tenants where id>? order by id limit 100",UUID.class,cursor);
            if (page.isEmpty()) return;
            for (UUID tenant:page) action.accept(tenant);
            cursor=page.getLast();
        }
    }
}
