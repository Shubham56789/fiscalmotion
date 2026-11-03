package com.fiscalmotion.ledger.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events", indexes = {
        @Index(name = "idx_outbox_processed", columnList = "processed")
})
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType; // e.g., "TRANSACTION"

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "type", nullable = false)
    private String type; // e.g., "TRANSACTION_CREATED"

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "processed", nullable = false)
    private boolean processed = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public OutboxEvent() {}

    public OutboxEvent(String aggregateType, String aggregateId, String type, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.type = type;
        this.payload = payload;
    }

    public UUID getId() { return id; }
    public String getAggregateId() { return aggregateId; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public boolean isProcessed() { return processed; }
    public void setProcessed(boolean processed) { this.processed = processed; }
}