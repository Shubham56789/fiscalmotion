package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.model.*;
import com.fiscalmotion.ledger.repository.AccountRepository;
import com.fiscalmotion.ledger.repository.OutboxEventRepository;
import com.fiscalmotion.ledger.repository.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class LedgerService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final Counter transactionCounter;
    private final Counter volumeCounter;

    public LedgerService(AccountRepository accountRepository,
                         TransactionRepository transactionRepository,
                         OutboxEventRepository outboxEventRepository,
                         MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.outboxEventRepository = outboxEventRepository;

        // Custom Prometheus Metrics
        this.transactionCounter = Counter.builder("fiscalmotion.transactions.total")
                .description("Total double-entry transactions executed successfully")
                .register(meterRegistry);

        this.volumeCounter = Counter.builder("fiscalmotion.transactions.volume.inr")
                .description("Total monetary volume processed")
                .register(meterRegistry);
    }

    @Transactional
    public Transaction executeTransfer(String referenceId, UUID sourceAccountId, UUID targetAccountId, BigDecimal amount) {
        transactionRepository.findByReferenceId(referenceId).ifPresent(tx -> {
            throw new IllegalStateException("Transaction already processed for reference: " + referenceId);
        });

        UUID firstLockId = sourceAccountId.compareTo(targetAccountId) < 0 ? sourceAccountId : targetAccountId;
        UUID secondLockId = sourceAccountId.compareTo(targetAccountId) < 0 ? targetAccountId : sourceAccountId;

        Account sourceAccount = accountRepository.findByIdWithLock(firstLockId)
                .filter(a -> a.getId().equals(sourceAccountId))
                .orElseGet(() -> accountRepository.findByIdWithLock(secondLockId).orElseThrow());

        Account targetAccount = sourceAccount.getId().equals(sourceAccountId)
                ? accountRepository.findByIdWithLock(secondLockId).orElseThrow()
                : accountRepository.findByIdWithLock(firstLockId).orElseThrow();

        Transaction transaction = new Transaction(referenceId, TransactionStatus.COMPLETED);
        transaction.addLedgerEntry(new LedgerEntry(sourceAccount, EntryType.DEBIT, amount));
        transaction.addLedgerEntry(new LedgerEntry(targetAccount, EntryType.CREDIT, amount));

        Transaction savedTransaction = transactionRepository.save(transaction);

        OutboxEvent outboxEvent = new OutboxEvent(
                "TRANSACTION",
                savedTransaction.getId().toString(),
                "TRANSACTION_CREATED",
                String.format("{\"transactionId\":\"%s\",\"referenceId\":\"%s\",\"status\":\"COMPLETED\"}",
                        savedTransaction.getId(), referenceId)
        );
        outboxEventRepository.save(outboxEvent);

        // Record metrics
        transactionCounter.increment();
        volumeCounter.increment(amount.doubleValue());

        return savedTransaction;
    }
}