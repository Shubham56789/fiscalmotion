
package com.fiscalmotion.ledger.validation;

import com.fiscalmotion.ledger.exception.InvalidJournalException;
import com.fiscalmotion.ledger.model.*;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@Component
public class JournalWriteValidator {

    public void validate(Transaction transaction) {

        if (transaction == null) {
            throw new InvalidJournalException(
                    "Transaction cannot be null"
            );
        }

        if (transaction.getStatus() != TransactionStatus.COMPLETED) {
            throw new InvalidJournalException(
                    "Only completed journals can be validated"
            );
        }

        if (transaction.getReferenceId() == null ||
                transaction.getReferenceId().isBlank()) {
            throw new InvalidJournalException(
                    "Transaction reference is required"
            );
        }

        List<LedgerEntry> entries = transaction.getEntries();

        if (entries == null || entries.size() != 2) {
            throw new InvalidJournalException(
                    "Completed transaction must contain exactly 2 entries"
            );
        }

        LedgerEntry debit = null;
        LedgerEntry credit = null;

        for (LedgerEntry entry : entries) {

            if (entry == null) {
                throw new InvalidJournalException(
                        "Null ledger entry"
                );
            }

            if (entry.getTransaction() != transaction) {
                throw new InvalidJournalException(
                        "Ledger entry belongs to another transaction"
                );
            }

            if (entry.getAccount() == null ||
                    entry.getAccount().getId() == null) {
                throw new InvalidJournalException(
                        "Ledger account is required"
                );
            }

            BigDecimal amount = entry.getAmount();

            // Reuse your existing monetary validation.
            try {
                MoneyValidator.requirePositive(amount);
            } catch (RuntimeException ex) {
                throw new InvalidJournalException(
                        "Invalid ledger amount: " + ex.getMessage()
                );
            }

            if (entry.getEntryType() == EntryType.DEBIT) {

                if (debit != null) {
                    throw new InvalidJournalException(
                            "Multiple debit entries are not allowed"
                    );
                }

                debit = entry;

            } else if (entry.getEntryType() == EntryType.CREDIT) {

                if (credit != null) {
                    throw new InvalidJournalException(
                            "Multiple credit entries are not allowed"
                    );
                }

                credit = entry;

            } else {
                throw new InvalidJournalException(
                        "Unknown ledger entry type"
                );
            }
        }

        if (debit == null || credit == null) {
            throw new InvalidJournalException(
                    "Exactly one debit and one credit are required"
            );
        }

        if (Objects.equals(
                debit.getAccount().getId(),
                credit.getAccount().getId()
        )) {
            throw new InvalidJournalException(
                    "Debit and credit accounts must be different"
            );
        }

        String debitCurrency = debit.getAccount().getCurrency();
        String creditCurrency = credit.getAccount().getCurrency();

        if (debitCurrency == null ||
                creditCurrency == null ||
                !debitCurrency.equalsIgnoreCase(creditCurrency)) {
            throw new InvalidJournalException(
                    "Ledger account currencies must match"
            );
        }

        if (debit.getAmount().compareTo(credit.getAmount()) != 0) {
            throw new InvalidJournalException(
                    "Debit and credit amounts must be equal"
            );
        }
    }
}
