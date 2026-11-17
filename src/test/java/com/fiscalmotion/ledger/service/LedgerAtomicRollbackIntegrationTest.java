
package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.exception.InvalidJournalException;
import com.fiscalmotion.ledger.model.Account;
import com.fiscalmotion.ledger.model.OutboxEvent;
import com.fiscalmotion.ledger.model.Transaction;
import com.fiscalmotion.ledger.repository.AccountRepository;
import com.fiscalmotion.ledger.repository.OutboxEventRepository;
import com.fiscalmotion.ledger.repository.TransactionRepository;

import com.fiscalmotion.ledger.validation.JournalWriteValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import org.springframework.dao.InvalidDataAccessApiUsageException;

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
class LedgerAtomicRollbackIntegrationTest {

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
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @MockitoSpyBean
    private OutboxEventRepository outboxEventRepository;

    @MockitoSpyBean
    private JournalWriteValidator journalWriteValidator;

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

    @Test
    void outboxFailureShouldRollbackBalancesAndLedger() {

        String reference = "rollback-" + UUID.randomUUID();

        long transactionsBefore = transactionRepository.count();
        long outboxEventsBefore = outboxEventRepository.count();

        // Simulate failure after the account balances have been flushed.
        doThrow(new IllegalStateException(
                "Simulated outbox persistence failure"
        )).when(outboxEventRepository).save(any(OutboxEvent.class));


        InvalidDataAccessApiUsageException exception = assertThrows(
                InvalidDataAccessApiUsageException.class,
                () -> ledgerService.executeTransfer(
                        reference,
                        sourceId,
                        targetId,
                        new BigDecimal("2000.0000")
                )
        );

        assertInstanceOf(
                IllegalStateException.class,
                exception.getCause()
        );

        assertEquals(
                "Simulated outbox persistence failure",
                exception.getCause().getMessage()
        );


        // Source debit must be rolled back.
        assertBalance(sourceId, "10000.0000");

        // Destination credit must be rolled back.
        assertBalance(targetId, "5000.0000");

        // Transaction and its cascading ledger entries
        // must not be committed.
        assertTrue(
                transactionRepository.findByReferenceId(reference)
                        .isEmpty()
        );

        assertEquals(
                transactionsBefore,
                transactionRepository.count()
        );

        // No outbox event should have been persisted.
        assertEquals(
                outboxEventsBefore,
                outboxEventRepository.count()
        );
    }

    private void assertBalance(UUID accountId, String expected) {
        BigDecimal actual = accountRepository.findById(accountId)
                .orElseThrow()
                .getBalance();

        assertEquals(
                0,
                actual.compareTo(new BigDecimal(expected))
        );
    }


    @Test
    void invalidJournalShouldRollbackEntireTransfer() {

        String reference = "INVALID-JOURNAL-" + UUID.randomUUID();

        long transactionsBefore = transactionRepository.count();
        long outboxBefore = outboxEventRepository.count();

        doThrow(new InvalidJournalException(
                "Simulated journal integrity violation"
        )).when(journalWriteValidator)
                .validate(any(Transaction.class));

        assertThrows(
                InvalidJournalException.class,
                () -> ledgerService.executeTransfer(
                        reference,
                        sourceId,
                        targetId,
                        new BigDecimal("2000.0000")
                )
        );

        // Balances must remain unchanged.
        assertBalance(sourceId, "10000.0000");
        assertBalance(targetId, "5000.0000");

        // No transaction or outbox event should persist.
        assertTrue(
                transactionRepository.findByReferenceId(reference).isEmpty()
        );

        assertEquals(
                transactionsBefore,
                transactionRepository.count()
        );

        assertEquals(
                outboxBefore,
                outboxEventRepository.count()
        );
    }

}
