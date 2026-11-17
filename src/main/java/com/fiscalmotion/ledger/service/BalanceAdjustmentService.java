
package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.exception.*;
import com.fiscalmotion.ledger.model.*;
import com.fiscalmotion.ledger.repository.*;

import com.fiscalmotion.ledger.validation.JournalWriteValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class BalanceAdjustmentService {

    private static final String SYSTEM_ACCOUNT =
            "FM-SYSTEM-OPENING";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final BalanceAdjustmentRepository adjustmentRepository;
    private final JournalWriteValidator journalWriteValidator;

    public BalanceAdjustmentService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            BalanceAdjustmentRepository adjustmentRepository,
            JournalWriteValidator journalWriteValidator
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.journalWriteValidator = journalWriteValidator;
    }

    @Transactional
    public BalanceAdjustment adjustBalance(
            UUID accountId,
            BigDecimal targetBalance,
            Long expectedVersion,
            String reason,
            String employee,
            String idempotencyKey
    ) {
        if (targetBalance == null ||
                targetBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidTransferException(
                    "Target balance cannot be negative"
            );
        }

        if (targetBalance.scale() > 4) {
            throw new InvalidTransferException(
                    "Maximum four decimal places allowed"
            );
        }

        if (expectedVersion == null || expectedVersion < 0) {
            throw new InvalidTransferException(
                    "Valid expectedVersion is required"
            );
        }

        if (reason == null || reason.isBlank()) {
            throw new InvalidTransferException(
                    "Adjustment reason is required"
            );
        }

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidTransferException(
                    "Idempotency-Key is required"
            );
        }

        if (idempotencyKey.length() > 150) {
            throw new InvalidTransferException(
                    "Idempotency-Key is too long"
            );
        }

        String key = "BALANCE-" + idempotencyKey.trim();

        // An existing key is never applied a second time.
        // Returning a previous result is permitted only
        // when the request parameters match.
        var existing = adjustmentRepository.findByIdempotencyKey(key);

        if (existing.isPresent()) {
            BalanceAdjustment previous = existing.get();

            if (!previous.getAccountId().equals(accountId)
                    || previous.getTargetBalance().compareTo(targetBalance) != 0
                    || !previous.getReason().equals(reason.trim())
                    || !previous.getEmployee().equals(employee)) {
                throw new ResourceConflictException(
                        "Idempotency key used for a different request"
                );
            }

            return previous;
        }

        Account customer = accountRepository.findById(accountId)
                .orElseThrow(() ->
                        new AccountNotFoundException(
                                "Customer account not found"
                        )
                );

        Account clearing = accountRepository
                .findByAccountNumber(SYSTEM_ACCOUNT)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "System clearing account missing"
                        )
                );

        if (customer.getId().equals(clearing.getId())) {
            throw new InvalidTransferException(
                    "System account cannot be adjusted"
            );
        }

        // Same deterministic lock order as LedgerService.
        UUID firstId = accountId.compareTo(clearing.getId()) < 0
                ? accountId : clearing.getId();

        UUID secondId = accountId.compareTo(clearing.getId()) < 0
                ? clearing.getId() : accountId;

        Account first = accountRepository.findByIdWithLock(firstId)
                .orElseThrow();

        Account second = accountRepository.findByIdWithLock(secondId)
                .orElseThrow();

        customer = first.getId().equals(accountId) ? first : second;
        clearing = first.getId().equals(clearing.getId()) ? first : second;

        if (!"INR".equals(customer.getCurrency())) {
            throw new InvalidTransferException(
                    "Only INR adjustments are supported"
            );
        }

        if (!expectedVersion.equals(customer.getVersion())) {
            throw new ResourceConflictException(
                    "Account balance has changed. Refresh and retry."
            );
        }

        BigDecimal before = customer.getBalance();
        BigDecimal difference = targetBalance.subtract(before);

        Transaction transaction = null;

        if (difference.signum() != 0) {
            BigDecimal amount = difference.abs();

            transaction = new Transaction(
                    key,
                    TransactionStatus.COMPLETED
            );

            if (difference.signum() > 0) {
                customer.credit(amount);

                clearing.setBalance(
                        clearing.getBalance().subtract(amount)
                );

                transaction.addLedgerEntry(
                        new LedgerEntry(customer, EntryType.CREDIT, amount)
                );
                transaction.addLedgerEntry(
                        new LedgerEntry(clearing, EntryType.DEBIT, amount)
                );
            } else {
                customer.debit(amount);

                clearing.setBalance(
                        clearing.getBalance().add(amount)
                );

                transaction.addLedgerEntry(
                        new LedgerEntry(customer, EntryType.DEBIT, amount)
                );
                transaction.addLedgerEntry(
                        new LedgerEntry(clearing, EntryType.CREDIT, amount)
                );
            }

            journalWriteValidator.validate(transaction);

            accountRepository.saveAll(List.of(customer, clearing));
            transaction = transactionRepository.saveAndFlush(transaction);
        }

        BalanceAdjustment adjustment = new BalanceAdjustment(
                key,
                accountId,
                employee,
                reason.trim(),
                before,
                targetBalance,
                transaction == null ? null : transaction.getId()
        );

        return adjustmentRepository.saveAndFlush(adjustment);
    }

    @Transactional(readOnly = true)
    public List<BalanceAdjustment> getHistory(UUID accountId) {
        return adjustmentRepository
                .findByAccountIdOrderByCreatedAtDesc(accountId);
    }
}
