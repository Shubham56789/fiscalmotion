
package com.fiscalmotion.ledger.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AccessRequestRepository
        extends JpaRepository<AccessRequest, UUID> {

    boolean existsByEmailIgnoreCaseAndStatus(
            String email,
            AccessRequest.Status status
    );

    List<AccessRequest> findAllByOrderByCreatedAtDesc();
}
