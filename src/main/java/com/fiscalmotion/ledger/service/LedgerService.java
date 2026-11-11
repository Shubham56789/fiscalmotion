package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.exception.AccountNotFoundException;
import com.fiscalmotion.ledger.exception.InsufficientBalanceException;
import com.fiscalmotion.ledger.exception.InvalidTransferException;
import com.fiscalmotion.ledger.exception.ResourceConflictException;
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

        this.transactionCounter = Counter.builder("fiscalmotion.transactions.total")
                .description("Total successful double-entry transactions")
                .register(meterRegistry);

        this.volumeCounter = Counter.builder("fiscalmotion.transactions.volume.inr")
                .description("Total amount processed in INR")
                .register(meterRegistry);
    }

    @Transactional
    public Transaction executeTransfer(String referenceId, UUID sourceAccountId, UUID targetAccountId, BigDecimal amount) {
        if (referenceId == null || referenceId.isBlank()) {
            throw new InvalidTransferException("referenceId is required");
        }
        if (sourceAccountId == null || targetAccountId == null) {
            throw new InvalidTransferException("sourceAccountId and targetAccountId are required");
        }
        if (sourceAccountId.equals(targetAccountId)) {
            throw new InvalidTransferException("sourceAccountId and targetAccountId cannot be the same");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("amount must be greater than zero");
        }

        transactionRepository.findByReferenceId(referenceId)
                .ifPresent(tx -> {
                    throw new ResourceConflictException("Transaction already processed for reference: " + referenceId);
                });

        UUID firstLockId = sourceAccountId.compareTo(targetAccountId) < 0 ? sourceAccountId : targetAccountId;
        UUID secondLockId = sourceAccountId.compareTo(targetAccountId) < 0 ? targetAccountId : sourceAccountId;

        Account firstAccount = accountRepository.findByIdWithLock(firstLockId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found"));
        Account secondAccount = accountRepository.findByIdWithLock(secondLockId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found"));

        Account sourceAccount = firstAccount.getId().equals(sourceAccountId)
                ? firstAccount : secondAccount;
        Account targetAccount = firstAccount.getId().equals(targetAccountId)
                ? firstAccount : secondAccount;

        if (!sourceAccount.getCurrency().equalsIgnoreCase(targetAccount.getCurrency())) {
            throw new InvalidTransferException("Accounts must be in the same currency");
        }
        if (sourceAccount.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException("Insufficient funds in source account");
        }

        sourceAccount.debit(amount);
        targetAccount.credit(amount);

        accountRepository.saveAll(java.util.List.of(sourceAccount, targetAccount));
        accountRepository.flush();

        Transaction transaction = new Transaction(referenceId, TransactionStatus.COMPLETED);
        transaction.addLedgerEntry(new LedgerEntry(sourceAccount, EntryType.DEBIT, amount));
        transaction.addLedgerEntry(new LedgerEntry(targetAccount, EntryType.CREDIT, amount));

        Transaction savedTransaction = transactionRepository.save(transaction);

        OutboxEvent event = new OutboxEvent(
                "TRANSACTION",
                savedTransaction.getId().toString(),
                "TRANSACTION_CREATED",
                String.format(
                        "{\"transactionId\":\"%s\",\"referenceId\":\"%s\",\"sourceAccountId\":\"%s\",\"targetAccountId\":\"%s\",\"amount\":\"%s\",\"status\":\"COMPLETED\"}",
                        savedTransaction.getId(),
                        referenceId,
                        sourceAccountId,
                        targetAccountId,
                        amount
                )
        );
        outboxEventRepository.save(event);

        transactionCounter.increment();
        volumeCounter.increment(amount.doubleValue());

        return savedTransaction;
    }
}