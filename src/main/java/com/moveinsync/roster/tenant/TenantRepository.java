package com.moveinsync.roster.tenant;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface TenantRepository extends Repository<Tenant, UUID> {
    boolean existsById(UUID tenantId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tenant t where t.id = :tenantId")
    Optional<Tenant> lockById(@Param("tenantId") UUID tenantId);
}
