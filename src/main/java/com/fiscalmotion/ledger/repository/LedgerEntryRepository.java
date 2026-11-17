
package com.fiscalmotion.ledger.repository;

import com.fiscalmotion.ledger.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.UUID;

public interface LedgerEntryRepository
        extends JpaRepository<LedgerEntry, UUID> {

    interface AccountLedgerTotals {
        BigDecimal getCredits();
        BigDecimal getDebits();
        Long getEntryCount();
    }

    @Query("""
        SELECT
            COALESCE(SUM(CASE
                WHEN e.entryType = com.fiscalmotion.ledger.model.EntryType.CREDIT
                THEN e.amount ELSE 0 END), 0) AS credits,
            COALESCE(SUM(CASE
                WHEN e.entryType = com.fiscalmotion.ledger.model.EntryType.DEBIT
                THEN e.amount ELSE 0 END), 0) AS debits,
            COUNT(e.id) AS entryCount
        FROM LedgerEntry e
        WHERE e.account.id = :accountId
          AND e.transaction.status =
              com.fiscalmotion.ledger.model.TransactionStatus.COMPLETED
        """)
    AccountLedgerTotals calculateTotals(
            @Param("accountId") UUID accountId
    );

    @Query("""
        SELECT COUNT(e.id)
        FROM LedgerEntry e
        WHERE e.account.id = :accountId
          AND e.transaction.referenceId = :openingReference
          AND e.transaction.status =
              com.fiscalmotion.ledger.model.TransactionStatus.COMPLETED
          AND e.entryType =
              com.fiscalmotion.ledger.model.EntryType.CREDIT
        """)
    long countOpeningEntries(
            @Param("accountId") UUID accountId,
            @Param("openingReference") String openingReference
    );
}
