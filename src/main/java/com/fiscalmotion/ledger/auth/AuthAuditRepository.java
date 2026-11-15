
package com.fiscalmotion.ledger.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AuthAuditRepository
        extends JpaRepository<AuthAuditEvent, UUID> {
}
