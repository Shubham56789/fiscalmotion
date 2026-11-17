
package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.dto.JournalIntegrityResult;
import com.fiscalmotion.ledger.model.EntryType;
import com.fiscalmotion.ledger.model.LedgerEntry;
import com.fiscalmotion.ledger.model.Transaction;
import com.fiscalmotion.ledger.model.TransactionStatus;
import com.fiscalmotion.ledger.repository.TransactionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class JournalIntegrityService {

    private final TransactionRepository transactionRepository;

    public JournalIntegrityService(
            TransactionRepository transactionRepository
    ) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public JournalIntegrityResult validate(String referenceId) {

        Transaction transaction = transactionRepository
                .findByReferenceIdWithEntries(referenceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction not found: " + referenceId
                ));

        return validateTransaction(transaction);
    }

    public JournalIntegrityResult validateTransaction(
            Transaction transaction
    ) {
        List<String> violations = new ArrayList<>();

        List<LedgerEntry> entries = transaction.getEntries();

        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;

        int debitCount = 0;
        int creditCount = 0;

        if (transaction.getStatus() != TransactionStatus.COMPLETED) {
            violations.add("Transaction is not COMPLETED");
        }

        if (entries.size() != 2) {
            violations.add(
                    "Expected exactly 2 entries, found " + entries.size()
            );
        }

        for (LedgerEntry entry : entries) {

            if (entry.getAccount() == null) {
                violations.add("Ledger entry has no account");
                continue;
            }

            if (entry.getAmount() == null) {
                violations.add("Ledger entry has no amount");
                continue;
            }

            BigDecimal amount = entry.getAmount();

            if (amount.signum() <= 0) {
                violations.add("Ledger amount must be positive");
            }

            try {
                BigDecimal normalized = amount.setScale(
                        4,
                        RoundingMode.UNNECESSARY
                );

                if (normalized.precision() > 19) {
                    violations.add(
                            "Ledger amount exceeds NUMERIC(19,4)"
                    );
                }
            } catch (ArithmeticException ex) {
                violations.add(
                        "Ledger amount has excessive decimal precision"
                );
            }

            if (entry.getEntryType() == EntryType.DEBIT) {
                debitCount++;
                debits = debits.add(amount);

            } else if (entry.getEntryType() == EntryType.CREDIT) {
                creditCount++;
                credits = credits.add(amount);

            } else {
                violations.add("Unknown ledger entry type");
            }

            if (entry.getTransaction() != transaction &&
                    !Objects.equals(
                            entry.getTransaction() == null
                                    ? null
                                    : entry.getTransaction().getId(),
                            transaction.getId()
                    )) {
                violations.add(
                        "Ledger entry belongs to another transaction"
                );
            }
        }

        if (debitCount != 1) {
            violations.add(
                    "Expected exactly 1 DEBIT, found " + debitCount
            );
        }

        if (creditCount != 1) {
            violations.add(
                    "Expected exactly 1 CREDIT, found " + creditCount
            );
        }

        if (debits.compareTo(credits) != 0) {
            violations.add(
                    "Debit and credit totals do not match"
            );
        }

        if (entries.size() == 2 &&
                entries.get(0).getAccount() != null &&
                entries.get(1).getAccount() != null) {

            var first = entries.get(0).getAccount();
            var second = entries.get(1).getAccount();

            if (Objects.equals(first.getId(), second.getId())) {
                violations.add(
                        "Debit and credit accounts must be different"
                );
            }

            if (!first.getCurrency()
                    .equalsIgnoreCase(second.getCurrency())) {
                violations.add(
                        "Ledger accounts have different currencies"
                );
            }
        }

        return new JournalIntegrityResult(
                transaction.getId(),
                transaction.getReferenceId(),
                violations.isEmpty(),
                entries.size(),
                debits,
                credits,
                List.copyOf(violations)
        );
    }
}
