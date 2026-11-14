package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.exception.InsufficientBalanceException;
import com.fiscalmotion.ledger.model.Account;
import com.fiscalmotion.ledger.model.EntryType;
import com.fiscalmotion.ledger.model.Transaction;
import com.fiscalmotion.ledger.repository.AccountRepository;
import com.fiscalmotion.ledger.repository.TransactionRepository;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
class LedgerPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("fiscalmotion_test")
                    .withUsername("testuser")
                    .withPassword("testpassword");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                postgres::getJdbcUrl
        );
        registry.add(
                "spring.datasource.username",
                postgres::getUsername
        );
        registry.add(
                "spring.datasource.password",
                postgres::getPassword
        );
    }

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private UUID sourceId;
    private UUID targetId;

    @BeforeEach
    void setup() {

        String suffix = UUID.randomUUID().toString();

        Account source = new Account(
                "SRC-" + suffix,
                "INR",
                new BigDecimal("10000.0000")
        );

        Account target = new Account(
                "DST-" + suffix,
                "INR",
                new BigDecimal("5000.0000")
        );

        sourceId = accountRepository.saveAndFlush(source).getId();
        targetId = accountRepository.saveAndFlush(target).getId();
    }

    private BigDecimal balance(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow()
                .getBalance();
    }

    @Test
    void successfulTransferShouldPreserveTotalBalance() {

        String reference = "success-" + UUID.randomUUID();

        Transaction transaction = ledgerService.executeTransfer(
                reference,
                sourceId,
                targetId,
                new BigDecimal("1500.0000")
        );

        assertNotNull(transaction.getId());

        assertEquals(
                0,
                balance(sourceId).compareTo(
                        new BigDecimal("8500.0000")
                )
        );

        assertEquals(
                0,
                balance(targetId).compareTo(
                        new BigDecimal("6500.0000")
                )
        );

        assertEquals(
                0,
                balance(sourceId)
                        .add(balance(targetId))
                        .compareTo(new BigDecimal("15000.0000"))
        );
    }

    @Test
    void successfulTransferShouldCreateBalancedLedgerEntries() {

        String reference = "entries-" + UUID.randomUUID();

        ledgerService.executeTransfer(
                reference,
                sourceId,
                targetId,
                new BigDecimal("250.0000")
        );

        Transaction transaction = transactionRepository
                .findByReferenceIdWithEntries(reference)
                .orElseThrow();

        assertEquals(2, transaction.getEntries().size());

        BigDecimal debits = transaction.getEntries()
                .stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .map(e -> e.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal credits = transaction.getEntries()
                .stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .map(e -> e.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, debits.compareTo(credits));
        assertEquals(0, debits.compareTo(
                new BigDecimal("250.0000")
        ));
    }

    @Test
    void duplicateReferenceShouldNotDebitTwice() {

        String reference = "duplicate-" + UUID.randomUUID();

        ledgerService.executeTransfer(
                reference,
                sourceId,
                targetId,
                new BigDecimal("1000.0000")
        );

        assertThrows(
                RuntimeException.class,
                () -> ledgerService.executeTransfer(
                        reference,
                        sourceId,
                        targetId,
                        new BigDecimal("1000.0000")
                )
        );

        assertEquals(
                0,
                balance(sourceId).compareTo(
                        new BigDecimal("9000.0000")
                )
        );

        assertEquals(
                0,
                balance(targetId).compareTo(
                        new BigDecimal("6000.0000")
                )
        );

        assertEquals(
                1,
                transactionRepository.findByReferenceId(reference)
                        .stream()
                        .count()
        );
    }

    @Test
    void insufficientBalanceShouldRollbackEntireTransfer() {

        String reference = "insufficient-" + UUID.randomUUID();

        assertThrows(
                InsufficientBalanceException.class,
                () -> ledgerService.executeTransfer(
                        reference,
                        sourceId,
                        targetId,
                        new BigDecimal("20000.0000")
                )
        );

        assertEquals(
                0,
                balance(sourceId).compareTo(
                        new BigDecimal("10000.0000")
                )
        );

        assertEquals(
                0,
                balance(targetId).compareTo(
                        new BigDecimal("5000.0000")
                )
        );

        assertTrue(
                transactionRepository.findByReferenceId(reference)
                        .isEmpty()
        );
    }

    @Test
    void concurrentDuplicateRequestsShouldCommitOnlyOnce()
            throws Exception {

        String reference = "concurrent-" + UUID.randomUUID();

        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            Future<Boolean> first = executor.submit(() -> {
                start.await();

                try {
                    ledgerService.executeTransfer(
                            reference,
                            sourceId,
                            targetId,
                            new BigDecimal("1000.0000")
                    );
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            });

            Future<Boolean> second = executor.submit(() -> {
                start.await();

                try {
                    ledgerService.executeTransfer(
                            reference,
                            sourceId,
                            targetId,
                            new BigDecimal("1000.0000")
                    );
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            });

            start.countDown();

            boolean firstSucceeded =
                    first.get(30, TimeUnit.SECONDS);

            boolean secondSucceeded =
                    second.get(30, TimeUnit.SECONDS);

            assertEquals(
                    1,
                    (firstSucceeded ? 1 : 0)
                            + (secondSucceeded ? 1 : 0),
                    "Exactly one concurrent transfer should succeed"
            );
        }

        assertEquals(
                0,
                balance(sourceId).compareTo(
                        new BigDecimal("9000.0000")
                )
        );

        assertEquals(
                0,
                balance(targetId).compareTo(
                        new BigDecimal("6000.0000")
                )
        );

        assertTrue(
                transactionRepository.findByReferenceId(reference)
                        .isPresent()
        );
    }
}