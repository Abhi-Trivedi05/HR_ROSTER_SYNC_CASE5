package com.moveinsync.roster.office;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class OfficeId implements Serializable {
    private UUID tenantId;
    private String code;
    public OfficeId() { }
    public OfficeId(UUID tenantId, String code) { this.tenantId = tenantId; this.code = code; }
    @Override public boolean equals(Object other) {
        return other instanceof OfficeId that && Objects.equals(tenantId, that.tenantId) && Objects.equals(code, that.code);
    }
    @Override public int hashCode() { return Objects.hash(tenantId, code); }
}
