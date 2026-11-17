
package com.fiscalmotion.ledger.repository;

import com.fiscalmotion.ledger.model.BalanceAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BalanceAdjustmentRepository
        extends JpaRepository<BalanceAdjustment, UUID> {

    Optional<BalanceAdjustment> findByIdempotencyKey(String key);

    List<BalanceAdjustment> findByAccountIdOrderByCreatedAtDesc(
            UUID accountId
    );
}
