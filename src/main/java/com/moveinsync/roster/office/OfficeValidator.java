package com.moveinsync.roster.office;

import com.moveinsync.roster.shared.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OfficeValidator {
    private final OfficeRepository offices;
    public OfficeValidator(OfficeRepository offices) { this.offices = offices; }
    public void requireActive(UUID tenantId, String code) {
        if (!offices.existsByTenantIdAndCodeAndActiveTrue(tenantId, code)) {
            throw ApiException.invalid("Office must exist and be active in this tenant");
        }
    }
}
