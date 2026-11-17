package com.fiscalmotion.ledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fiscalmotion.ledger.dto.AccountReconciliationResult;
import com.fiscalmotion.ledger.dto.TransactionCreatedEvent;
import com.fiscalmotion.ledger.exception.InsufficientBalanceException;
import com.fiscalmotion.ledger.exception.InvalidTransferException;
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
import java.util.ArrayList;
import java.util.List;
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

    @Autowired
    private LedgerReconciliationService reconciliationService;

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


    @Test
    void transferWithExcessDecimalPlacesShouldBeRejected() {

        String reference = "precision-" + UUID.randomUUID();

        assertThrows(
                InvalidTransferException.class,
                () -> ledgerService.executeTransfer(
                        reference,
                        sourceId,
                        targetId,
                        new BigDecimal("100.12345")
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
                transactionRepository.findByReferenceId(reference).isEmpty()
        );
    }

    @Test
    void transferWithValidPrecisionShouldSucceed() {

        String reference = "valid-precision-" + UUID.randomUUID();

        ledgerService.executeTransfer(
                reference,
                sourceId,
                targetId,
                new BigDecimal("100.1234")
        );

        assertEquals(
                0,
                balance(sourceId).compareTo(
                        new BigDecimal("9899.8766")
                )
        );

        assertEquals(
                0,
                balance(targetId).compareTo(
                        new BigDecimal("5100.1234")
                )
        );
    }


    @Test
    void concurrentTransfersShouldNotLoseMoney() throws Exception {

        int numberOfTransfers = 100;
        BigDecimal transferAmount = new BigDecimal("50.0000");

        ExecutorService executor = Executors.newFixedThreadPool(20);

        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Transaction>> futures = new ArrayList<>();
        List<String> references = new ArrayList<>();

        try {
            for (int i = 0; i < numberOfTransfers; i++) {

                String reference = "concurrent-" + UUID.randomUUID();
                references.add(reference);

                futures.add(executor.submit(() -> {

                    // Only the first 20 tasks wait at the barrier.
                    // Remaining tasks execute when a thread becomes free.
                    if (ready.getCount() > 0) {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Start barrier timed out"
                            );
                        }
                    }

                    return ledgerService.executeTransfer(
                            reference,
                            sourceId,
                            targetId,
                            transferAmount
                    );
                }));
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Workers did not become ready"
            );

            start.countDown();

            int successCount = 0;

            for (Future<Transaction> future : futures) {
                Transaction transaction = future.get(
                        120,
                        TimeUnit.SECONDS
                );

                assertNotNull(transaction.getId());
                successCount++;
            }

            assertEquals(numberOfTransfers, successCount);

            // 10000 - (100 * 50) = 5000
            assertEquals(
                    0,
                    balance(sourceId).compareTo(
                            new BigDecimal("5000.0000")
                    )
            );

            // 5000 + (100 * 50) = 10000
            assertEquals(
                    0,
                    balance(targetId).compareTo(
                            new BigDecimal("10000.0000")
                    )
            );

            // No money should be created or lost.
            BigDecimal totalBalance = balance(sourceId)
                    .add(balance(targetId));

            assertEquals(
                    0,
                    totalBalance.compareTo(
                            new BigDecimal("15000.0000")
                    )
            );

            // Every successful transfer must have a persisted
            // transaction with two balanced ledger entries.
            for (String reference : references) {

                Transaction transaction = transactionRepository
                        .findByReferenceIdWithEntries(reference)
                        .orElseThrow();

                assertEquals(2, transaction.getEntries().size());

                BigDecimal debitTotal = BigDecimal.ZERO;
                BigDecimal creditTotal = BigDecimal.ZERO;

                for (var entry : transaction.getEntries()) {
                    if (entry.getEntryType() == EntryType.DEBIT) {
                        debitTotal = debitTotal.add(entry.getAmount());
                    } else if (entry.getEntryType() == EntryType.CREDIT) {
                        creditTotal = creditTotal.add(entry.getAmount());
                    }
                }

                assertEquals(
                        0,
                        debitTotal.compareTo(creditTotal)
                );

                assertEquals(
                        0,
                        debitTotal.compareTo(transferAmount)
                );
            }

        } finally {
            start.countDown();
            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(15, TimeUnit.SECONDS),
                    "Transfer workers did not terminate"
            );
        }
    }


    @Test
    void concurrentTransfersShouldNeverOverdrawAccount()
            throws Exception {

        final int requestCount = 100;
        final int workerCount = 20;

        final BigDecimal amount = new BigDecimal("200.0000");

        // Existing setup:
        // Source = 10000
        // Target = 5000

        ExecutorService executor =
                Executors.newFixedThreadPool(workerCount);

        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();
        List<String> references = new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {

                String reference =
                        "overdraft-" + UUID.randomUUID();

                references.add(reference);

                futures.add(executor.submit(() -> {

                    // Start the first wave together.
                    // Remaining tasks execute as workers become free.
                    if (ready.getCount() > 0) {
                        ready.countDown();

                        if (!start.await(15, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Start barrier timed out"
                            );
                        }
                    }

                    try {
                        ledgerService.executeTransfer(
                                reference,
                                sourceId,
                                targetId,
                                amount
                        );

                        return true;

                    } catch (InsufficientBalanceException ex) {

                        // This is the only expected business rejection.
                        return false;
                    }
                }));
            }

            assertTrue(
                    ready.await(15, TimeUnit.SECONDS),
                    "Workers failed to become ready"
            );

            start.countDown();

            int successful = 0;
            int rejected = 0;

            for (Future<Boolean> future : futures) {

                boolean result = future.get(
                        120,
                        TimeUnit.SECONDS
                );

                if (result) {
                    successful++;
                } else {
                    rejected++;
                }
            }

            // Exactly 50 transfers can be funded.
            assertEquals(50, successful);
            assertEquals(50, rejected);

            assertEquals(requestCount, successful + rejected);

            // Source must never be overdrawn.
            assertEquals(
                    0,
                    balance(sourceId).compareTo(
                            new BigDecimal("0.0000")
                    )
            );

            // Destination receives 50 * 200 = 10000.
            assertEquals(
                    0,
                    balance(targetId).compareTo(
                            new BigDecimal("15000.0000")
                    )
            );

            // Money conservation.
            BigDecimal total =
                    balance(sourceId).add(balance(targetId));

            assertEquals(
                    0,
                    total.compareTo(new BigDecimal("15000.0000"))
            );

            int persistedTransactions = 0;

            for (String reference : references) {

                var saved = transactionRepository
                        .findByReferenceIdWithEntries(reference);

                if (saved.isEmpty()) {
                    continue;
                }

                persistedTransactions++;

                Transaction transaction = saved.get();

                // Each committed transfer has two journal entries.
                assertEquals(
                        2,
                        transaction.getEntries().size()
                );

                BigDecimal debitTotal = BigDecimal.ZERO;
                BigDecimal creditTotal = BigDecimal.ZERO;

                for (var entry : transaction.getEntries()) {

                    assertEquals(
                            0,
                            entry.getAmount().compareTo(amount)
                    );

                    if (entry.getEntryType() == EntryType.DEBIT) {
                        debitTotal = debitTotal.add(
                                entry.getAmount()
                        );
                    } else if (
                            entry.getEntryType() == EntryType.CREDIT
                    ) {
                        creditTotal = creditTotal.add(
                                entry.getAmount()
                        );
                    } else {
                        fail("Unexpected ledger entry type");
                    }
                }

                assertEquals(
                        0,
                        debitTotal.compareTo(creditTotal)
                );

                assertEquals(
                        0,
                        debitTotal.compareTo(amount)
                );
            }

            // Rejected transfers must not create committed transactions.
            assertEquals(successful, persistedTransactions);

        } finally {

            start.countDown();
            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(
                            30,
                            TimeUnit.SECONDS
                    ),
                    "Transfer workers failed to terminate"
            );
        }
    }

    @Test
    void legacyAccountWithoutOpeningEntryShouldBeMarkedIncomplete() {

        // sourceId comes from the existing test setup,
        // which directly creates an account with an initial balance.

        var result = reconciliationService.reconcileAccount(sourceId);

        assertEquals(
                AccountReconciliationResult.Status.INCOMPLETE_HISTORY,
                result.status()
        );
    }


    @Test
    void transactionEventShouldEscapeSpecialCharacters()
            throws Exception {

        ObjectMapper mapper = new ObjectMapper();

        TransactionCreatedEvent event =
                new TransactionCreatedEvent(
                        UUID.randomUUID(),
                        "PAY-\"SPECIAL\"-001",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new BigDecimal("2500.0000"),
                        "COMPLETED"
                );

        String json = mapper.writeValueAsString(event);

        JsonNode parsed = mapper.readTree(json);

        assertEquals(
                "PAY-\"SPECIAL\"-001",
                parsed.get("referenceId").asText()
        );

        assertEquals(
                "2500.0000",
                parsed.get("amount").asText()
        );
    }

}