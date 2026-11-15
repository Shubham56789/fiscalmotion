
package com.fiscalmotion.ledger.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fm_access_requests")
public class AccessRequest {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    private Instant createdAt = Instant.now();

    private Instant decidedAt;

    protected AccessRequest() {}

    public AccessRequest(
            String email,
            String name,
            String reason
    ) {
        this.email = email;
        this.name = name;
        this.reason = reason;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getReason() { return reason; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public void decide(Status status) {
        this.status = status;
        this.decidedAt = Instant.now();
    }

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }
}
