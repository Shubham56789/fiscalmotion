package com.fiscalmotion.ledger.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "bulk_transfer_items",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_bulk_batch_row",
                        columnNames = {"batch_id", "row_number"}
                ),
                @UniqueConstraint(
                        name = "uk_bulk_reference",
                        columnNames = {"reference_id"}
                )
        }
)
public class BulkTransferItem {

    public enum Status {
        PENDING,
        PROCESSING,
        SUCCESS,
        FAILED,
        UNKNOWN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private BulkTransferBatch batch;

    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(name = "reference_id", nullable = false, unique = true)
    private String referenceId;

    @Column(name = "source_account")
    private String sourceAccount;

    @Column(name = "target_account")
    private String targetAccount;

    @Column(name = "amount_text", length = 1000)
    private String amountText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "result_message", length = 1000)
    private String resultMessage;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected BulkTransferItem() {
    }

    public BulkTransferItem(
            BulkTransferBatch batch,
            int rowNumber,
            String referenceId,
            String sourceAccount,
            String targetAccount,
            BigDecimal amount
    ) {
        this.batch = batch;
        this.rowNumber = rowNumber;
        this.referenceId = referenceId;
        this.sourceAccount = sourceAccount;
        this.targetAccount = targetAccount;
        this.amountText = amount == null
                ? null
                : amount.toString();
        this.updatedAt = Instant.now();
    }

    public void markProcessing() {
        this.status = Status.PROCESSING;
        this.resultMessage = null;
        this.updatedAt = Instant.now();
    }

    public void markSuccess(UUID transactionId) {
        if (transactionId == null) {
            throw new IllegalArgumentException(
                    "Transaction ID is required"
            );
        }

        this.status = Status.SUCCESS;
        this.transactionId = transactionId;
        this.resultMessage = null;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String message) {
        this.status = Status.FAILED;
        this.resultMessage = limit(message);
        this.updatedAt = Instant.now();
    }

    public void markUnknown(String message) {
        this.status = Status.UNKNOWN;
        this.resultMessage = limit(message);
        this.updatedAt = Instant.now();
    }

    private String limit(String message) {
        if (message == null) {
            return null;
        }

        return message.substring(
                0,
                Math.min(1000, message.length())
        );
    }

    public UUID getId() {
        return id;
    }

    public BulkTransferBatch getBatch() {
        return batch;
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public String getSourceAccount() {
        return sourceAccount;
    }

    public String getTargetAccount() {
        return targetAccount;
    }

    public String getAmountText() {
        return amountText;
    }

    public BigDecimal getAmount() {
        return amountText == null
                ? null
                : new BigDecimal(amountText);
    }

    public Status getStatus() {
        return status;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getResultMessage() {
        return resultMessage;
    }
}