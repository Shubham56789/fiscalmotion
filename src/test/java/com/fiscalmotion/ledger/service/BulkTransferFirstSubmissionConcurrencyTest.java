
package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.dto.BulkTransferRequest;
import com.fiscalmotion.ledger.model.Account;
import com.fiscalmotion.ledger.model.BulkTransferItem;
import com.fiscalmotion.ledger.repository.AccountRepository;
import com.fiscalmotion.ledger.repository.BulkTransferBatchRepository;
import com.fiscalmotion.ledger.repository.BulkTransferItemRepository;
import com.fiscalmotion.ledger.repository.TransactionRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
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
class BulkTransferFirstSubmissionConcurrencyTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("fiscalmotion_concurrency_test")
                    .withUsername("testuser")
                    .withPassword("testpassword");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private BulkTransferService bulkService;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private BulkTransferBatchRepository batches;

    @Autowired
    private BulkTransferItemRepository items;

    @Autowired
    private TransactionRepository transactions;

    @Test
    void simultaneousFirstSubmissionsMustNotDoubleDebit()
            throws Exception {

        String suffix = UUID.randomUUID().toString();
        String batchId = "race-" + suffix;

        Account source = accounts.saveAndFlush(
                new Account(
                        "SRC-" + suffix,
                        "INR",
                        new BigDecimal("10000.0000")
                )
        );

        Account target = accounts.saveAndFlush(
                new Account(
                        "DST-" + suffix,
                        "INR",
                        new BigDecimal("5000.0000")
                )
        );

        BulkTransferRequest request = new BulkTransferRequest(
                List.of(
                        new BulkTransferRequest.Item(
                                source.getAccountNumber(),
                                target.getAccountNumber(),
                                new BigDecimal("1000.0000")
                        ),
                        new BulkTransferRequest.Item(
                                source.getAccountNumber(),
                                target.getAccountNumber(),
                                new BigDecimal("500.0000")
                        )
                )
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            Future<Throwable> first = executor.submit(() -> {
                ready.countDown();
                start.await();

                try {
                    bulkService.process(batchId, request, "testadmin");
                    return null;
                } catch (Exception e) {
                    return e;
                }
            });

            Future<Throwable> second = executor.submit(() -> {
                ready.countDown();
                start.await();

                try {
                    bulkService.process(batchId, request, "testadmin");
                    return null;
                } catch (Exception e) {
                    return e;
                }
            });

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Both workers must be ready"
            );

            start.countDown();

            Throwable firstError = first.get(60, TimeUnit.SECONDS);
            Throwable secondError = second.get(60, TimeUnit.SECONDS);

            if (firstError != null) {
                System.out.println(
                        "First request: " + firstError
                );
            }

            if (secondError != null) {
                System.out.println(
                        "Second request: " + secondError
                );
            }

            // One request may be rejected during a race.
            // But at least one should successfully process the batch.
            assertTrue(
                    firstError == null || secondError == null,
                    "Both submissions failed"
            );
        }

        // Verify final balances from fresh repository reads.
        BigDecimal sourceBalance = accounts.findById(source.getId())
                .orElseThrow()
                .getBalance();

        BigDecimal targetBalance = accounts.findById(target.getId())
                .orElseThrow()
                .getBalance();

        assertEquals(
                0,
                sourceBalance.compareTo(
                        new BigDecimal("8500.0000")
                ),
                "Source must be debited exactly once per row"
        );

        assertEquals(
                0,
                targetBalance.compareTo(
                        new BigDecimal("6500.0000")
                ),
                "Target must be credited exactly once per row"
        );

        assertEquals(
                0,
                sourceBalance.add(targetBalance)
                        .compareTo(new BigDecimal("15000.0000")),
                "Total money must remain unchanged"
        );

        assertTrue(batches.findById(batchId).isPresent());

        List<BulkTransferItem> savedRows =
                items.findByBatch_BatchIdOrderByRowNumber(batchId);

        assertEquals(2, savedRows.size());

        for (int i = 0; i < 2; i++) {

            String reference = "bulk:" + batchId + ":" + i;

            assertTrue(
                    transactions.findByReferenceId(reference).isPresent(),
                    "Missing committed transaction: " + reference
            );

            assertEquals(
                    BulkTransferItem.Status.SUCCESS,
                    savedRows.get(i).getStatus()
            );
        }
    }
}
