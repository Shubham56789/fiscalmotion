package com.fiscalmotion.ledger.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlement_summaries")
public class SettlementSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false, unique = true)
    private UUID accountId;

    @Column(name = "total_settled_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalSettledAmount = BigDecimal.ZERO;

    @Column(name = "processed_transactions_count", nullable = false)
    private long processedTransactionsCount = 0;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt = Instant.now();

    public SettlementSummary() {}

    public SettlementSummary(UUID accountId) {
        this.accountId = accountId;
    }

    public void addSettlement(BigDecimal amount) {
        this.totalSettledAmount = this.totalSettledAmount.add(amount);
        this.processedTransactionsCount++;
        this.lastUpdatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public BigDecimal getTotalSettledAmount() { return totalSettledAmount; }
    public long getProcessedTransactionsCount() { return processedTransactionsCount; }
    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
}