package com.moveinsync.roster.outbox;

import com.moveinsync.roster.shared.TenantTransactions;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {
    public record Claimed(ChangeMessage message,UUID token,int attempts) { }
    private final JdbcTemplate jdbc;
    private final TenantTransactions transactions;
    public OutboxRepository(JdbcTemplate jdbc,TenantTransactions transactions) { this.jdbc=jdbc;this.transactions=transactions; }
    public void append(UUID tenant,UUID employee,String type,long version) {
        // Database transaction time defines the lifecycle boundary; source timestamps are audit metadata, not ordering clocks.
        jdbc.update("insert into outbox(tenant_id,id,employee_id,event_type,source_version,effective_at) values (?,?,?,?,?,now())",
                tenant,UUID.randomUUID(),employee,type,version);
    }
    public List<Claimed> claim(UUID tenant,int limit,int leaseSeconds) {
        return transactions.inTenant(tenant,false,()-> {
            UUID token=UUID.randomUUID();
            return jdbc.query("""
                with candidates as (
                 select id from outbox where tenant_id=? and
                  ((status='PENDING' and available_at<=now()) or (status='CLAIMED' and lease_until<now()))
                 order by created_at,id for update skip locked limit ?
                ) update outbox o set status='CLAIMED',claim_token=?,lease_until=now()+(? * interval '1 second'),attempts=o.attempts+1
                from candidates c where o.tenant_id=? and o.id=c.id
                returning o.id,o.employee_id,o.event_type,o.source_version,o.effective_at,o.attempts
                """,(rs,n)->new Claimed(new ChangeMessage(tenant,rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                        rs.getString(3),rs.getLong(4),rs.getTimestamp(5).toInstant()),token,rs.getInt(6)),tenant,limit,token,leaseSeconds,tenant);
        });
    }
    public void complete(Claimed claimed) {
        var m=claimed.message();
        transactions.inTenant(m.tenantId(),false,()->jdbc.update("update outbox set status='PUBLISHED',claim_token=null,lease_until=null,last_error=null where tenant_id=? and id=? and claim_token=? and status='CLAIMED'",
                m.tenantId(),m.eventId(),claimed.token()));
    }
    public void fail(Claimed claimed,int maxAttempts,String code) {
        var m=claimed.message();
        long delay=Math.min(3600,1L<<Math.min(12,claimed.attempts()));
        transactions.inTenant(m.tenantId(),false,()->jdbc.update("update outbox set status=?,available_at=now()+(? * interval '1 second'),claim_token=null,lease_until=null,last_error=? where tenant_id=? and id=? and claim_token=? and status='CLAIMED'",
                claimed.attempts()>=maxAttempts?"DEAD":"PENDING",delay,code,m.tenantId(),m.eventId(),claimed.token()));
    }
    public long backlog(UUID tenant) { return jdbc.queryForObject("select count(*) from outbox where tenant_id=? and status<>'PUBLISHED'",Long.class,tenant); }
    public void expireExhausted(UUID tenant,int maxAttempts) {
        transactions.inTenant(tenant,false,()->jdbc.update("update outbox set status='DEAD',last_error='LEASE_RETRY_EXHAUSTED',claim_token=null,lease_until=null where tenant_id=? and status='CLAIMED' and lease_until<now() and attempts>=?",tenant,maxAttempts));
    }
    public int requeue(UUID tenant,UUID event) {
        return jdbc.update("update outbox set status='PENDING',attempts=0,available_at=now(),last_error=null where tenant_id=? and id=? and status='DEAD'",tenant,event);
    }
}
