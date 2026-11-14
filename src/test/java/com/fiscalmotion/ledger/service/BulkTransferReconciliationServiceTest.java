package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.model.BulkTransferBatch;
import com.fiscalmotion.ledger.model.BulkTransferItem;
import com.fiscalmotion.ledger.model.Transaction;
import com.fiscalmotion.ledger.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BulkTransferReconciliationServiceTest {

    @Mock
    private BulkTransferPersistenceService persistence;

    @Mock
    private TransactionRepository transactions;

    private BulkTransferReconciliationService reconciliation;

    @BeforeEach
    void setup() {
        reconciliation = new BulkTransferReconciliationService(
                persistence,
                transactions
        );
    }

    private BulkTransferItem createProcessingRow() {

        BulkTransferBatch batch = new BulkTransferBatch(
                "test-batch-001",
                "test-fingerprint",
                1,
                "testadmin"
        );

        BulkTransferItem item = new BulkTransferItem(
                batch,
                2,
                "bulk:test-batch-001:0",
                "ACC001",
                "ACC002",
                new BigDecimal("100.00")
        );

        item.markProcessing();

        return item;
    }

    @Test
    void committedTransactionShouldBeMarkedSuccessful() {

        BulkTransferItem item = createProcessingRow();

        UUID transactionId = UUID.randomUUID();

        Transaction transaction = mock(Transaction.class);

        when(transaction.getId()).thenReturn(transactionId);

        when(transactions.findByReferenceId(item.getReferenceId()))
                .thenReturn(Optional.of(transaction));

        reconciliation.reconcileRow(item);

        verify(persistence).markSuccess(
                item.getId(),
                transactionId
        );

        verifyNoMoreInteractions(persistence);
    }

    @Test
    void missingTransactionShouldNotTriggerNewTransfer() {

        BulkTransferItem item = createProcessingRow();

        when(transactions.findByReferenceId(item.getReferenceId()))
                .thenReturn(Optional.empty());

        reconciliation.reconcileRow(item);

        verify(transactions).findByReferenceId(
                item.getReferenceId()
        );

        verifyNoInteractions(persistence);
    }

    @Test
    void databaseFailureShouldLeaveRowUnresolved() {

        BulkTransferItem item = createProcessingRow();

        when(transactions.findByReferenceId(item.getReferenceId()))
                .thenThrow(new RuntimeException(
                        "Database temporarily unavailable"
                ));

        reconciliation.reconcileRow(item);

        verifyNoInteractions(persistence);
    }

    @Test
    void completedRowShouldNotBeReconciledAgain() {

        BulkTransferItem item = createProcessingRow();

        item.markSuccess(UUID.randomUUID());

        reconciliation.reconcileRow(item);

        verifyNoInteractions(transactions);
        verifyNoInteractions(persistence);
    }
}