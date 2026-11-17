
package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.dto.AccountReconciliationResult;
import com.fiscalmotion.ledger.model.Account;
import com.fiscalmotion.ledger.repository.AccountRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.listener.auto-startup=false",
        "spring.task.scheduling.enabled=false",
        "fiscalmotion.bulk.enabled=false",
        "fiscalmotion.security.admin.username=testadmin",
        "fiscalmotion.security.admin.password=testAdminPassword123",
        "fiscalmotion.security.user.username=testuser",
        "fiscalmotion.security.user.password=testUserPassword123"
})
class LedgerReconciliationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("fiscalmotion_test")
                    .withUsername("testuser")
                    .withPassword("testpassword");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AccountManagementService accountManagementService;

    @Autowired
    private BalanceAdjustmentService balanceAdjustmentService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerReconciliationService reconciliationService;

    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void initializeSystemAccount() {

        String systemNumber =
                AccountManagementService.SYSTEM_ACCOUNT_NUMBER;

        if (accountRepository.findByAccountNumber(systemNumber).isEmpty()) {

            Account systemAccount = new Account(
                    systemNumber,
                    "INR",
                    BigDecimal.ZERO
            );

            accountRepository.saveAndFlush(systemAccount);
        }
    }

    @Test
    void openingTransferAndAdjustmentShouldReconcile() {

        String suffix = UUID.randomUUID().toString();

        // STEP 1: Create two customer accounts.
        Account accountA = accountManagementService.createAccount(
                "A-" + suffix,
                "Customer A"
        );

        Account accountB = accountManagementService.createAccount(
                "B-" + suffix,
                "Customer B"
        );

        assertBalance(accountA.getId(), "100000.0000");
        assertBalance(accountB.getId(), "100000.0000");

        // STEP 2: Both opening balances must reconcile.
        assertMatched(accountA.getId(), "100000.0000");
        assertMatched(accountB.getId(), "100000.0000");

        // STEP 3: Transfer 25000 from A to B.
        ledgerService.executeTransfer(
                "TRANSFER-" + UUID.randomUUID(),
                accountA.getId(),
                accountB.getId(),
                new BigDecimal("25000.0000")
        );

        assertBalance(accountA.getId(), "75000.0000");
        assertBalance(accountB.getId(), "125000.0000");

        // STEP 4: Both accounts must still reconcile.
        assertMatched(accountA.getId(), "75000.0000");
        assertMatched(accountB.getId(), "125000.0000");

        // STEP 5: Adjust Account A to 90000.
        Account latestA = accountRepository
                .findById(accountA.getId())
                .orElseThrow();

        balanceAdjustmentService.adjustBalance(
                accountA.getId(),
                new BigDecimal("90000.0000"),
                latestA.getVersion(),
                "Approved balance correction",
                "test-employee",
                UUID.randomUUID().toString()
        );

        // STEP 6: Verify final balances.
        assertBalance(accountA.getId(), "90000.0000");
        assertBalance(accountB.getId(), "125000.0000");

        // STEP 7: Verify final ledger reconciliation.
        assertMatched(accountA.getId(), "90000.0000");
        assertMatched(accountB.getId(), "125000.0000");
    }

    @Test
    void reconciliationShouldDetectTamperedBalance() {

        Account account = accountManagementService.createAccount(
                "TAMPER-" + UUID.randomUUID(),
                "Tamper Test"
        );

        // Initially, balance and journal agree.
        assertMatched(account.getId(), "100000.0000");

        // Simulate an unauthorized or buggy direct database update.
        Account persisted = accountRepository
                .findById(account.getId())
                .orElseThrow();

        persisted.setBalance(new BigDecimal("95000.0000"));
        accountRepository.saveAndFlush(persisted);

        // The journal still contains the original 100000 credit.
        AccountReconciliationResult result =
                reconciliationService.reconcileAccount(account.getId());

        assertEquals(
                AccountReconciliationResult.Status.MISMATCHED,
                result.status()
        );

        assertMoneyEquals("95000.0000", result.storedBalance());
        assertMoneyEquals("100000.0000", result.ledgerBalance());
        assertMoneyEquals("-5000.0000", result.difference());
    }

    private void assertMatched(UUID accountId, String expectedBalance) {

        AccountReconciliationResult result =
                reconciliationService.reconcileAccount(accountId);

        assertEquals(
                AccountReconciliationResult.Status.MATCHED,
                result.status()
        );

        assertMoneyEquals(expectedBalance, result.storedBalance());
        assertMoneyEquals(expectedBalance, result.ledgerBalance());
        assertMoneyEquals("0.0000", result.difference());

        assertTrue(
                result.entryCount() > 0,
                "Reconciled account must have ledger entries"
        );
    }

    private void assertBalance(UUID accountId, String expected) {

        BigDecimal actual = accountRepository
                .findById(accountId)
                .orElseThrow()
                .getBalance();

        assertMoneyEquals(expected, actual);
    }

    private void assertMoneyEquals(String expected, BigDecimal actual) {

        assertNotNull(actual);

        assertEquals(
                0,
                new BigDecimal(expected).compareTo(actual)
        );
    }
}
