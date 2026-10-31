package com.moveinsync.roster.tenant;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class Tenant {
    @Id private UUID id;
    @Column(nullable = false, length = 160) private String name;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    protected Tenant() { }
    public UUID getId() { return id; }
}
