
package com.fiscalmotion.ledger.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fm_auth_audit")
public class AuthAuditEvent {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 60)
    private String action;

    @Column(length = 254)
    private String actor;

    @Column(length = 254)
    private String subject;

    private Instant occurredAt = Instant.now();

    protected AuthAuditEvent() {}

    public AuthAuditEvent(
            String action,
            String actor,
            String subject
    ) {
        this.action = action;
        this.actor = actor;
        this.subject = subject;
    }
}
