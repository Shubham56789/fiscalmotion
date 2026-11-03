package com.fiscalmotion.ledger.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true)
    private String accountNumber;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Account() {}

    public Account(String accountNumber, String currency) {
        this.accountNumber = accountNumber;
        this.currency = currency;
    }

    public UUID getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}