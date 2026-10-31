package com.moveinsync.roster.office;

import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface OfficeRepository extends Repository<Office, OfficeId> {
    boolean existsByTenantIdAndCodeAndActiveTrue(UUID tenantId, String code);
}
