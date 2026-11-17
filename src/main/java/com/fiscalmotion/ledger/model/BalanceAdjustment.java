
package com.fiscalmotion.ledger.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "balance_adjustments",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_balance_adjustment_key",
                columnNames = "idempotency_key"
        )
)
public class BalanceAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "employee", nullable = false)
    private String employee;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "previous_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal previousBalance;

    @Column(name = "target_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal targetBalance;

    @Column(name = "adjustment_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal adjustmentAmount;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected BalanceAdjustment() {}

    public BalanceAdjustment(
            String idempotencyKey,
            UUID accountId,
            String employee,
            String reason,
            BigDecimal previousBalance,
            BigDecimal targetBalance,
            UUID transactionId
    ) {
        this.idempotencyKey = idempotencyKey;
        this.accountId = accountId;
        this.employee = employee;
        this.reason = reason;
        this.previousBalance = previousBalance;
        this.targetBalance = targetBalance;
        this.adjustmentAmount = targetBalance.subtract(previousBalance);
        this.transactionId = transactionId;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getAccountId() { return accountId; }
    public String getEmployee() { return employee; }
    public String getReason() { return reason; }
    public BigDecimal getPreviousBalance() { return previousBalance; }
    public BigDecimal getTargetBalance() { return targetBalance; }
    public BigDecimal getAdjustmentAmount() { return adjustmentAmount; }
    public UUID getTransactionId() { return transactionId; }
    public Instant getCreatedAt() { return createdAt; }
}
