
package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.dto.BulkTransferRequest;
import com.fiscalmotion.ledger.dto.BulkTransferResponse;
import com.fiscalmotion.ledger.exception.ResourceConflictException;
import com.fiscalmotion.ledger.model.Account;
import com.fiscalmotion.ledger.model.BulkTransferBatch;
import com.fiscalmotion.ledger.model.BulkTransferItem;
import com.fiscalmotion.ledger.repository.AccountRepository;
import com.fiscalmotion.ledger.repository.BulkTransferBatchRepository;
import com.fiscalmotion.ledger.repository.BulkTransferItemRepository;
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
class BulkTransferPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("fiscalmotion_bulk_test")
                    .withUsername("testuser")
                    .withPassword("testpassword");

    @DynamicPropertySource
    static void databaseProperties(
            DynamicPropertyRegistry registry
    ) {
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
    private BulkTransferService bulkService;

    @Autowired
    private BulkTransferPersistenceService persistence;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private BulkTransferBatchRepository batches;

    @Autowired
    private BulkTransferItemRepository items;

    private String sourceNumber;
    private String targetNumber;

    private UUID sourceId;
    private UUID targetId;

    private static final String ADMIN = "testadmin";

    private static final BigDecimal INITIAL_SOURCE =
            new BigDecimal("100000.0000");

    private static final BigDecimal INITIAL_TARGET =
            new BigDecimal("50000.0000");

    @BeforeEach
    void setup() {

        String suffix = UUID.randomUUID().toString();

        sourceNumber = "SRC-" + suffix;
        targetNumber = "DST-" + suffix;

        Account source = new Account(
                sourceNumber,
                "INR",
                INITIAL_SOURCE
        );

        Account target = new Account(
                targetNumber,
                "INR",
                INITIAL_TARGET
        );

        sourceId = accounts.saveAndFlush(source).getId();
        targetId = accounts.saveAndFlush(target).getId();
    }

    private String batchId() {
        return "batch-" + UUID.randomUUID();
    }

    private BulkTransferRequest.Item row(
            BigDecimal amount
    ) {
        return new BulkTransferRequest.Item(
                sourceNumber,
                targetNumber,
                amount
        );
    }

    private BulkTransferRequest request(
            BulkTransferRequest.Item... rows
    ) {
        return new BulkTransferRequest(List.of(rows));
    }

    private BigDecimal balance(UUID id) {
        return accounts.findById(id)
                .orElseThrow()
                .getBalance();
    }

    private void assertBalance(
            UUID accountId,
            String expected
    ) {
        assertEquals(
                0,
                balance(accountId)
                        .compareTo(new BigDecimal(expected))
        );
    }

    private void assertTotalConserved() {
        assertEquals(
                0,
                balance(sourceId)
                        .add(balance(targetId))
                        .compareTo(
                                INITIAL_SOURCE.add(INITIAL_TARGET)
                        )
        );
    }

    @Test
    void hundredTransfersShouldCompleteSuccessfully() {

        String id = batchId();

        List<BulkTransferRequest.Item> rows =
                new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            rows.add(row(new BigDecimal("10.0000")));
        }

        BulkTransferResponse response = bulkService.process(
                id,
                new BulkTransferRequest(rows),
                ADMIN
        );

        assertEquals(100, response.totalCount());
        assertEquals(100, response.successCount());
        assertEquals(0, response.failureCount());
        assertEquals(0, response.pendingCount());
        assertEquals(100, response.results().size());

        assertBalance(sourceId, "99000.0000");
        assertBalance(targetId, "51000.0000");
        assertTotalConserved();

        BulkTransferBatch batch =
                batches.findById(id).orElseThrow();

        assertEquals(
                BulkTransferBatch.Status.COMPLETED,
                batch.getStatus()
        );

        assertEquals(100, batch.getSuccessCount());
        assertEquals(0, batch.getFailureCount());
        assertEquals(0, batch.getPendingCount());
        assertNotNull(batch.getCompletedAt());

        for (int i = 0; i < 100; i++) {
            assertTrue(
                    transactions.findByReferenceId(
                            "bulk:" + id + ":" + i
                    ).isPresent()
            );
        }
    }

    @Test
    void duplicateBatchShouldNotTransferAgain() {

        String id = batchId();

        BulkTransferRequest request = request(
                row(new BigDecimal("100.0000")),
                row(new BigDecimal("200.0000"))
        );

        BulkTransferResponse first =
                bulkService.process(id, request, ADMIN);

        BigDecimal sourceAfterFirst = balance(sourceId);
        BigDecimal targetAfterFirst = balance(targetId);

        BulkTransferResponse second =
                bulkService.process(id, request, ADMIN);

        assertEquals(2, first.successCount());
        assertEquals(2, second.successCount());

        assertEquals(
                0,
                sourceAfterFirst.compareTo(balance(sourceId))
        );

        assertEquals(
                0,
                targetAfterFirst.compareTo(balance(targetId))
        );

        assertEquals(
                1,
                transactions.findByReferenceId(
                        "bulk:" + id + ":0"
                ).stream().count()
        );

        assertEquals(
                1,
                transactions.findByReferenceId(
                        "bulk:" + id + ":1"
                ).stream().count()
        );

        assertTotalConserved();

        assertThrows(
                ResourceConflictException.class,
                () -> bulkService.process(
                        id,
                        request(
                                row(new BigDecimal("999.0000"))
                        ),
                        ADMIN
                )
        );
    }

    @Test
    void invalidRowsShouldFailIndependently() {

        String id = batchId();

        BulkTransferRequest request = request(
                row(new BigDecimal("100.0000")),
                row(new BigDecimal("-50.0000")),
                new BulkTransferRequest.Item(
                        sourceNumber,
                        "MISSING-ACCOUNT",
                        new BigDecimal("100.0000")
                ),
                row(new BigDecimal("200.0000"))
        );

        BulkTransferResponse response =
                bulkService.process(id, request, ADMIN);

        assertEquals(4, response.totalCount());
        assertEquals(2, response.successCount());
        assertEquals(2, response.failureCount());
        assertEquals(0, response.pendingCount());

        assertBalance(sourceId, "99700.0000");
        assertBalance(targetId, "50300.0000");
        assertTotalConserved();

        BulkTransferBatch batch =
                batches.findById(id).orElseThrow();

        assertEquals(
                BulkTransferBatch.Status.PARTIAL_SUCCESS,
                batch.getStatus()
        );

        List<BulkTransferItem> savedRows =
                items.findByBatch_BatchIdOrderByRowNumber(id);

        assertEquals(
                BulkTransferItem.Status.SUCCESS,
                savedRows.get(0).getStatus()
        );

        assertEquals(
                BulkTransferItem.Status.FAILED,
                savedRows.get(1).getStatus()
        );

        assertEquals(
                BulkTransferItem.Status.FAILED,
                savedRows.get(2).getStatus()
        );

        assertEquals(
                BulkTransferItem.Status.SUCCESS,
                savedRows.get(3).getStatus()
        );

        assertTrue(
                transactions.findByReferenceId(
                        "bulk:" + id + ":1"
                ).isEmpty()
        );

        assertTrue(
                transactions.findByReferenceId(
                        "bulk:" + id + ":2"
                ).isEmpty()
        );
    }

    @Test
    void committedTransferShouldBeRecoveredWithoutSecondDebit() {

        String id = batchId();

        BulkTransferRequest request = request(
                row(new BigDecimal("500.0000"))
        );

        // Simulate a previously registered batch.
        persistence.createBatch(
                id,
                "recovery-test-fingerprint",
                request,
                ADMIN
        );

        BulkTransferItem item =
                persistence.getItems(id).get(0);

        // The row was claimed before execution.
        assertTrue(persistence.claim(item.getId()));

        // Simulate a ledger transaction that committed
        // before the bulk-row success update.
        var committed = ledgerService.executeTransfer(
                item.getReferenceId(),
                sourceId,
                targetId,
                new BigDecimal("500.0000")
        );

        assertNotNull(committed.getId());

        assertBalance(sourceId, "99500.0000");
        assertBalance(targetId, "50500.0000");

        // Reconcile without executing the transfer again.
        var reconciliation =
                new BulkTransferReconciliationService(
                        persistence,
                        transactions
                );

        reconciliation.reconcile(id);
        persistence.refreshBatchStatus(id);

        BulkTransferItem recovered =
                persistence.getItems(id).get(0);

        assertEquals(
                BulkTransferItem.Status.SUCCESS,
                recovered.getStatus()
        );

        assertEquals(
                committed.getId(),
                recovered.getTransactionId()
        );

        assertBalance(sourceId, "99500.0000");
        assertBalance(targetId, "50500.0000");
        assertTotalConserved();

        BulkTransferBatch batch =
                batches.findById(id).orElseThrow();

        assertEquals(
                BulkTransferBatch.Status.COMPLETED,
                batch.getStatus()
        );
    }

    @Test
    void batchShouldRecordSubmittingAdministrator() {

        String id = batchId();

        bulkService.process(
                id,
                request(row(new BigDecimal("100.0000"))),
                ADMIN
        );

        BulkTransferBatch batch =
                batches.findById(id).orElseThrow();

        assertEquals(ADMIN, batch.getSubmittedBy());
        assertNotNull(batch.getCreatedAt());
        assertNotNull(batch.getUpdatedAt());
        assertNotNull(batch.getCompletedAt());

        assertThrows(
                ResourceConflictException.class,
                () -> bulkService.process(
                        id,
                        request(row(new BigDecimal("100.0000"))),
                        "another-admin"
                )
        );
    }

    @Test
    void concurrentBatchSubmissionsShouldNotDoubleDebit()
            throws Exception {

        String id = batchId();

        BulkTransferRequest request = request(
                row(new BigDecimal("1000.0000")),
                row(new BigDecimal("2000.0000"))
        );

        // Register first so this test isolates concurrent
        // row claiming rather than batch-creation races.
        bulkService.process(id, request, ADMIN);

        BigDecimal sourceBefore = balance(sourceId);
        BigDecimal targetBefore = balance(targetId);

        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            Future<?> first = executor.submit(() -> {
                start.await();
                bulkService.process(id, request, ADMIN);
                return null;
            });

            Future<?> second = executor.submit(() -> {
                start.await();
                bulkService.process(id, request, ADMIN);
                return null;
            });

            start.countDown();

            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }

        assertEquals(
                0,
                sourceBefore.compareTo(balance(sourceId))
        );

        assertEquals(
                0,
                targetBefore.compareTo(balance(targetId))
        );

        assertTotalConserved();

        assertEquals(
                1,
                transactions.findByReferenceId(
                        "bulk:" + id + ":0"
                ).stream().count()
        );

        assertEquals(
                1,
                transactions.findByReferenceId(
                        "bulk:" + id + ":1"
                ).stream().count()
        );
    }
}
