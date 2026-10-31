package com.moveinsync.roster.office;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "offices")
@IdClass(OfficeId.class)
public class Office {
    @Id @Column(nullable = false) private UUID tenantId;
    @Id @Column(length = 40) private String code;
    @Column(nullable = false, length = 160) private String name;
    @Column(nullable = false) private boolean active;
    protected Office() { }
}
