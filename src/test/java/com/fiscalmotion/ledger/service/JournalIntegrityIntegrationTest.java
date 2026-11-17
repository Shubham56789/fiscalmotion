
package com.fiscalmotion.ledger.validation;

import com.fiscalmotion.ledger.exception.InvalidJournalException;
import com.fiscalmotion.ledger.model.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JournalWriteValidatorTest {

    private JournalWriteValidator validator;

    private Account source;
    private Account target;

    @BeforeEach
    void setup() {
        validator = new JournalWriteValidator();

        source = new Account(
                "SRC-" + UUID.randomUUID(),
                "INR",
                new BigDecimal("10000.0000")
        );

        target = new Account(
                "DST-" + UUID.randomUUID(),
                "INR",
                new BigDecimal("5000.0000")
        );

        // These are unit-test fixtures, not persisted entities.
        // The validator requires real account identities, so
        // assign IDs using ReflectionTestUtils.
        org.springframework.test.util.ReflectionTestUtils.setField(
                source, "id", UUID.randomUUID()
        );

        org.springframework.test.util.ReflectionTestUtils.setField(
                target, "id", UUID.randomUUID()
        );
    }

    @Test
    void validJournalShouldPass() {

        Transaction transaction = transaction(
                "500.0000",
                "500.0000"
        );

        assertDoesNotThrow(
                () -> validator.validate(transaction)
        );
    }

    @Test
    void unequalAmountsShouldFail() {

        Transaction transaction = transaction(
                "500.0000",
                "400.0000"
        );

        InvalidJournalException exception = assertThrows(
                InvalidJournalException.class,
                () -> validator.validate(transaction)
        );

        assertTrue(
                exception.getMessage().contains(
                        "amounts must be equal"
                )
        );
    }

    @Test
    void missingCreditShouldFail() {

        Transaction transaction = new Transaction(
                "MISSING-" + UUID.randomUUID(),
                TransactionStatus.COMPLETED
        );

        transaction.addLedgerEntry(
                new LedgerEntry(
                        source,
                        EntryType.DEBIT,
                        new BigDecimal("500.0000")
                )
        );

        assertThrows(
                InvalidJournalException.class,
                () -> validator.validate(transaction)
        );
    }

    @Test
    void negativeAmountShouldFail() {

        Transaction transaction = transaction(
                "-500.0000",
                "-500.0000"
        );

        assertThrows(
                InvalidJournalException.class,
                () -> validator.validate(transaction)
        );
    }

    @Test
    void excessivePrecisionShouldFail() {

        Transaction transaction = transaction(
                "500.12345",
                "500.12345"
        );

        assertThrows(
                InvalidJournalException.class,
                () -> validator.validate(transaction)
        );
    }

    @Test
    void sameAccountShouldFail() {

        Transaction transaction = new Transaction(
                "SAME-" + UUID.randomUUID(),
                TransactionStatus.COMPLETED
        );

        transaction.addLedgerEntry(
                new LedgerEntry(
                        source,
                        EntryType.DEBIT,
                        new BigDecimal("500.0000")
                )
        );

        transaction.addLedgerEntry(
                new LedgerEntry(
                        source,
                        EntryType.CREDIT,
                        new BigDecimal("500.0000")
                )
        );

        assertThrows(
                InvalidJournalException.class,
                () -> validator.validate(transaction)
        );
    }

    private Transaction transaction(
            String debitAmount,
            String creditAmount
    ) {

        Transaction transaction = new Transaction(
                "TEST-" + UUID.randomUUID(),
                TransactionStatus.COMPLETED
        );

        transaction.addLedgerEntry(
                new LedgerEntry(
                        source,
                        EntryType.DEBIT,
                        new BigDecimal(debitAmount)
                )
        );

        transaction.addLedgerEntry(
                new LedgerEntry(
                        target,
                        EntryType.CREDIT,
                        new BigDecimal(creditAmount)
                )
        );

        return transaction;
    }
}
