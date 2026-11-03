package com.fiscalmotion.ledger.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_trx_reference_id", columnList = "reference_id", unique = true)
})
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "reference_id", nullable = false, unique = true)
    private String referenceId; // Idempotency key from client

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransactionStatus status;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LedgerEntry> entries = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Transaction() {}

    public Transaction(String referenceId, TransactionStatus status) {
        this.referenceId = referenceId;
        this.status = status;
    }

    public void addLedgerEntry(LedgerEntry entry) {
        entries.add(entry);
        entry.setTransaction(this);
    }

    public UUID getId() { return id; }
    public String getReferenceId() { return referenceId; }
    public TransactionStatus getStatus() { return status; }
    public void setStatus(TransactionStatus status) { this.status = status; }
    public List<LedgerEntry> getEntries() { return entries; }
    public Instant getCreatedAt() { return createdAt; }
}