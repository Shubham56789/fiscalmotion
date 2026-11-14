package com.fiscalmotion.ledger.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "bulk_transfer_batches")
public class BulkTransferBatch {

    public enum Status {
        PENDING,
        PROCESSING,
        COMPLETED,
        PARTIAL_SUCCESS,
        FAILED
    }

    @Id
    @Column(name = "batch_id", length = 100, nullable = false)
    private String batchId;

    @Column(
            name = "request_fingerprint",
            length = 64,
            nullable = false
    )
    private String requestFingerprint;

    @Column(name = "submitted_by", length = 150, nullable = false)
    private String submittedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "success_count", nullable = false)
    private int successCount;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "pending_count", nullable = false)
    private int pendingCount;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private Long version;

    protected BulkTransferBatch() {
    }

    public BulkTransferBatch(
            String batchId,
            String requestFingerprint,
            int totalCount,
            String submittedBy
    ) {
        this.batchId = batchId;
        this.requestFingerprint = requestFingerprint;
        this.totalCount = totalCount;
        this.submittedBy = submittedBy;
        this.pendingCount = totalCount;
        this.status = Status.PENDING;

        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateSummary(
            int successes,
            int failures,
            int pending
    ) {
        if (successes < 0 || failures < 0 || pending < 0
                || successes + failures + pending != totalCount) {
            throw new IllegalArgumentException(
                    "Invalid bulk-transfer summary counts"
            );
        }

        this.successCount = successes;
        this.failureCount = failures;
        this.pendingCount = pending;

        if (pending > 0) {
            this.status = successes == 0 && failures == 0
                    && pending == totalCount
                    ? Status.PENDING
                    : Status.PROCESSING;

            this.completedAt = null;

        } else if (failures == 0) {
            this.status = Status.COMPLETED;

        } else if (successes == 0) {
            this.status = Status.FAILED;

        } else {
            this.status = Status.PARTIAL_SUCCESS;
        }

        this.updatedAt = Instant.now();

        if (pending == 0 && this.completedAt == null) {
            this.completedAt = this.updatedAt;
        }
    }

    public String getBatchId() {
        return batchId;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public Status getStatus() {
        return status;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public int getPendingCount() {
        return pendingCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}