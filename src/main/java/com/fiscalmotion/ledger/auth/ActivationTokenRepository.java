
package com.fiscalmotion.ledger.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ActivationTokenRepository
        extends JpaRepository<ActivationToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select t
        from ActivationToken t
        join fetch t.user
        where t.tokenHash = :hash
        """)
    Optional<ActivationToken> lockByHash(
            @Param("hash") String hash
    );
}
