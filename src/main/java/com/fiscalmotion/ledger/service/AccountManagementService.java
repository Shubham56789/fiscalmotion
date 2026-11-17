
package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.exception.*;
import com.fiscalmotion.ledger.model.*;
import com.fiscalmotion.ledger.repository.*;

import com.fiscalmotion.ledger.validation.JournalWriteValidator;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class AccountManagementService {

    public static final BigDecimal OPENING_BALANCE =
            new BigDecimal("100000.00");

    public static final String SYSTEM_ACCOUNT_NUMBER =
            "FM-SYSTEM-OPENING";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final JournalWriteValidator journalWriteValidator;

    public AccountManagementService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            JournalWriteValidator journalWriteValidator
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.journalWriteValidator = journalWriteValidator;
    }

    @Transactional
    public Account createAccount(
            String accountNumber,
            String accountHolderName
    ) {
        String normalizedNumber =
                accountNumber.trim().toUpperCase();

        String normalizedName =
                accountHolderName.trim();

        if (normalizedNumber.equals(SYSTEM_ACCOUNT_NUMBER)) {
            throw new InvalidTransferException(
                    "Reserved system account number"
            );
        }

        if (
                accountRepository
                        .findByAccountNumber(normalizedNumber)
                        .isPresent()
        ) {
            throw new ResourceConflictException(
                    "Account number already exists"
            );
        }

        // The system account must already exist.
        Account systemAccount = accountRepository
                .findByAccountNumber(SYSTEM_ACCOUNT_NUMBER)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "System opening account not initialized"
                        )
                );

        // Lock the balancing account before modifying it.
        systemAccount = accountRepository
                .findByIdWithLock(systemAccount.getId())
                .orElseThrow();

        Account customerAccount = new Account(
                normalizedNumber,
                normalizedName,
                "INR"
        );

        customerAccount.credit(OPENING_BALANCE);

        // Save first to establish its database identity.
        customerAccount =
                accountRepository.saveAndFlush(customerAccount);

        // Record the offsetting system movement.
        // This clearing account is allowed to carry a
        // negative balance, unlike customer accounts.
        systemAccount.setBalance(
                systemAccount.getBalance()
                        .subtract(OPENING_BALANCE)
        );

        accountRepository.save(systemAccount);

        String referenceId =
                "OPENING-" + customerAccount.getId();

        Transaction transaction = new Transaction(
                referenceId,
                TransactionStatus.COMPLETED
        );

        // Preserve FiscalMotion's existing movement convention:
        // CREDIT increases a customer account;
        // DEBIT decreases an account.
        transaction.addLedgerEntry(
                new LedgerEntry(
                        customerAccount,
                        EntryType.CREDIT,
                        OPENING_BALANCE
                )
        );

        transaction.addLedgerEntry(
                new LedgerEntry(
                        systemAccount,
                        EntryType.DEBIT,
                        OPENING_BALANCE
                )
        );

        journalWriteValidator.validate(transaction);

        transactionRepository.save(transaction);

        return customerAccount;
    }

    @Transactional(readOnly = true)
    public List<Account> getAllAccounts() {
        return accountRepository.findAll(
                        Sort.by(
                                Sort.Direction.ASC,
                                "accountNumber"
                        )
                ).stream()
                .filter(account ->
                        !SYSTEM_ACCOUNT_NUMBER.equals(
                                account.getAccountNumber()
                        )
                )
                .toList();
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID id) {
        Account account = accountRepository
                .findById(id)
                .orElseThrow(() ->
                        new AccountNotFoundException(
                                "Account not found: " + id
                        )
                );

        if (SYSTEM_ACCOUNT_NUMBER.equals(
                account.getAccountNumber()
        )) {
            throw new AccountNotFoundException(
                    "Account not found: " + id
            );
        }

        return account;
    }
}
