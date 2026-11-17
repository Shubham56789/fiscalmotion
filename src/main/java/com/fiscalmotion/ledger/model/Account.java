
package com.fiscalmotion.ledger.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "accounts",
        indexes = {
                @Index(
                        name = "idx_accounts_account_number",
                        columnList = "account_number",
                        unique = true
                )
        }
)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(
            name = "account_number",
            nullable = false,
            unique = true
    )
    private String accountNumber;

    @Column(name = "account_holder_name", length = 120)
    private String accountHolderName;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(
            name = "balance",
            nullable = false,
            precision = 19,
            scale = 4
    )
    private BigDecimal balance = BigDecimal.ZERO;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Account() {}

    public Account(
            String accountNumber,
            String currency,
            BigDecimal initialBalance
    ) {
        this.accountNumber = accountNumber;
        this.currency = currency.toUpperCase();
        this.balance = initialBalance;
    }

    public Account(
            String accountNumber,
            String accountHolderName,
            String currency
    ) {
        this(accountNumber, currency, BigDecimal.ZERO);
        this.accountHolderName = accountHolderName;
    }

    public void credit(BigDecimal amount) {
        validateAmount(amount);
        balance = balance.add(amount);
        updatedAt = Instant.now();
    }

    public void debit(BigDecimal amount) {
        validateAmount(amount);

        if (balance.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    "Insufficient funds"
            );
        }

        balance = balance.subtract(amount);
        updatedAt = Instant.now();
    }

    private void validateAmount(BigDecimal amount) {
        if (
                amount == null ||
                        amount.compareTo(BigDecimal.ZERO) <= 0
        ) {
            throw new IllegalArgumentException(
                    "Amount must be positive"
            );
        }
    }

    public UUID getId() { return id; }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getAccountHolderName() {
        return accountHolderName;
    }

    public String getCurrency() { return currency; }

    public BigDecimal getBalance() { return balance; }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public Long getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
