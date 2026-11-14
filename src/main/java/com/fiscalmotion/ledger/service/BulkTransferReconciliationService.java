package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.model.BulkTransferItem;
import com.fiscalmotion.ledger.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BulkTransferReconciliationService {

    private final BulkTransferPersistenceService persistence;
    private final TransactionRepository transactions;

    public BulkTransferReconciliationService(
            BulkTransferPersistenceService persistence,
            TransactionRepository transactions
    ) {
        this.persistence = persistence;
        this.transactions = transactions;
    }

    public void reconcile(String batchId) {

        List<BulkTransferItem> rows = persistence.getItems(batchId);

        for (BulkTransferItem row : rows) {

            if (row.getStatus() != BulkTransferItem.Status.PROCESSING
                    && row.getStatus() != BulkTransferItem.Status.UNKNOWN) {
                continue;
            }

            reconcileRow(row);
        }
    }

    public void reconcileRow(BulkTransferItem row) {

        if (row.getStatus() != BulkTransferItem.Status.PROCESSING
                && row.getStatus() != BulkTransferItem.Status.UNKNOWN) {
            return;
        }

        try {
            var committed = transactions.findByReferenceId(
                    row.getReferenceId()
            );

            if (committed.isPresent()) {

                persistence.markSuccess(
                        row.getId(),
                        committed.get().getId()
                );
            }

            // No committed transaction found:
            // Leave the row unresolved.
            // Never automatically execute the transfer again.

        } catch (Exception e) {

            // An infrastructure failure must not trigger a debit.
            // The row remains unresolved for later reconciliation.
        }
    }
}