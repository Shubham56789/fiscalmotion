
package com.fiscalmotion.ledger.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "fm_app_users",
        uniqueConstraints = @UniqueConstraint(columnNames = "email")
)
public class AppUser {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    protected AppUser() {}

    public AppUser(
            String email,
            String name,
            Role role,
            Status status
    ) {
        this.email = email;
        this.name = name;
        this.role = role;
        this.status = status;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public Status getStatus() { return status; }

    public void activate(String hash) {
        this.passwordHash = hash;
        this.status = Status.ACTIVE;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public enum Role {
        OWNER,
        USER
    }

    public enum Status {
        PENDING_ACTIVATION,
        ACTIVE,
        SUSPENDED,
        REVOKED
    }
}
