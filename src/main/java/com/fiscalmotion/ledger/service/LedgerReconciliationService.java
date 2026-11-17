
package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.dto.AccountReconciliationResult;
import com.fiscalmotion.ledger.exception.AccountNotFoundException;
import com.fiscalmotion.ledger.model.Account;
import com.fiscalmotion.ledger.repository.AccountRepository;
import com.fiscalmotion.ledger.repository.LedgerEntryRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class LedgerReconciliationService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerReconciliationService(
            AccountRepository accountRepository,
            LedgerEntryRepository ledgerEntryRepository
    ) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public AccountReconciliationResult reconcileAccount(UUID accountId) {

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() ->
                        new AccountNotFoundException("Account not found")
                );

        var totals = ledgerEntryRepository.calculateTotals(accountId);

        BigDecimal credits = totals.getCredits();
        BigDecimal debits = totals.getDebits();

        BigDecimal expectedBalance = credits.subtract(debits);
        BigDecimal storedBalance = account.getBalance();

        BigDecimal difference = storedBalance.subtract(expectedBalance);

        String openingReference = "OPENING-" + accountId;

        boolean hasOpeningEntry =
                ledgerEntryRepository.countOpeningEntries(
                        accountId,
                        openingReference
                ) == 1;

        AccountReconciliationResult.Status status;

        if (!hasOpeningEntry) {
            status = AccountReconciliationResult.Status.INCOMPLETE_HISTORY;
        } else if (difference.compareTo(BigDecimal.ZERO) == 0) {
            status = AccountReconciliationResult.Status.MATCHED;
        } else {
            status = AccountReconciliationResult.Status.MISMATCHED;
        }

        return new AccountReconciliationResult(
                account.getId(),
                account.getAccountNumber(),
                storedBalance,
                expectedBalance,
                difference,
                totals.getEntryCount(),
                status
        );
    }
}
