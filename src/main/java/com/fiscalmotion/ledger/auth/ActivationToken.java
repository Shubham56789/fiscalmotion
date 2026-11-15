
package com.fiscalmotion.ledger.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fm_activation_tokens")
public class ActivationToken {

    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(
            optional = false,
            fetch = FetchType.LAZY
    )
    private AppUser user;

    @Column(
            nullable = false,
            unique = true,
            length = 64
    )
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;

    protected ActivationToken() {}

    public ActivationToken(
            AppUser user,
            String tokenHash,
            Instant expiresAt
    ) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public AppUser getUser() {
        return user;
    }

    public boolean valid() {
        return usedAt == null &&
                Instant.now().isBefore(expiresAt);
    }

    public void consume() {
        usedAt = Instant.now();
    }
}
