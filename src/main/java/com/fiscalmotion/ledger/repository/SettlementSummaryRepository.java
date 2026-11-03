package com.fiscalmotion.ledger.repository;

import com.fiscalmotion.ledger.model.SettlementSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SettlementSummaryRepository extends JpaRepository<SettlementSummary, UUID> {
    Optional<SettlementSummary> findByAccountId(UUID accountId);
}