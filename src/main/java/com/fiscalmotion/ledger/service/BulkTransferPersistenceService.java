package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.dto.BulkTransferRequest;
import com.fiscalmotion.ledger.model.BulkTransferBatch;
import com.fiscalmotion.ledger.model.BulkTransferItem;
import com.fiscalmotion.ledger.repository.BulkTransferBatchRepository;
import com.fiscalmotion.ledger.repository.BulkTransferItemRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class BulkTransferPersistenceService {

    private final BulkTransferBatchRepository batches;
    private final BulkTransferItemRepository items;

    public BulkTransferPersistenceService(
            BulkTransferBatchRepository batches,
            BulkTransferItemRepository items
    ) {
        this.batches = batches;
        this.items = items;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createBatch(
            String batchId,
            String fingerprint,
            BulkTransferRequest request,
            String submittedBy
    ) {
        BulkTransferBatch batch = new BulkTransferBatch(
                batchId,
                fingerprint,
                request.transfers().size(),
                submittedBy
        );

        batches.saveAndFlush(batch);

        for (int index = 0; index < request.transfers().size(); index++) {
            var transfer = request.transfers().get(index);

            BulkTransferItem item = new BulkTransferItem(
                    batch,
                    index + 2,
                    "bulk:" + batchId + ":" + index,
                    trimOrNull(transfer.sourceAccount()),
                    trimOrNull(transfer.targetAccount()),
                    transfer.amount()
            );

            items.save(item);
        }

        items.flush();
    }

    private String trimOrNull(String value) {
        return value == null ? null : value.trim();
    }

    @Transactional(readOnly = true)
    public BulkTransferBatch getBatch(String batchId) {
        return batches.findById(batchId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Batch not found: " + batchId
                        )
                );
    }

    @Transactional(readOnly = true)
    public List<BulkTransferItem> getItems(String batchId) {
        return items.findByBatch_BatchIdOrderByRowNumber(batchId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID itemId) {
        BulkTransferItem item = locked(itemId);

        if (item.getStatus() != BulkTransferItem.Status.PENDING) {
            return false;
        }

        item.markProcessing();
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(UUID itemId, UUID transactionId) {
        BulkTransferItem item = locked(itemId);

        if (item.getStatus() == BulkTransferItem.Status.SUCCESS) {
            if (!transactionId.equals(item.getTransactionId())) {
                throw new IllegalStateException(
                        "Conflicting transaction for successful row"
                );
            }
            return;
        }

        if (item.getStatus() == BulkTransferItem.Status.FAILED) {
            throw new IllegalStateException(
                    "Cannot change a failed row to successful"
            );
        }

        if (item.getStatus() != BulkTransferItem.Status.PROCESSING
                && item.getStatus() != BulkTransferItem.Status.UNKNOWN) {
            throw new IllegalStateException(
                    "Invalid success transition: " + item.getStatus()
            );
        }

        item.markSuccess(transactionId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID itemId, String message) {
        BulkTransferItem item = locked(itemId);

        if (item.getStatus() != BulkTransferItem.Status.PROCESSING) {
            return;
        }

        item.markFailed(message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnknown(UUID itemId, String message) {
        BulkTransferItem item = locked(itemId);

        if (item.getStatus() != BulkTransferItem.Status.PROCESSING) {
            return;
        }

        item.markUnknown(message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshBatchStatus(String batchId) {
        BulkTransferBatch batch = batches.findById(batchId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Batch not found: " + batchId
                        )
                );

        List<BulkTransferItem> rows =
                items.findByBatch_BatchIdOrderByRowNumber(batchId);

        int successes = 0;
        int failures = 0;
        int pending = 0;

        for (BulkTransferItem row : rows) {
            switch (row.getStatus()) {
                case SUCCESS -> successes++;
                case FAILED -> failures++;
                case PENDING, PROCESSING, UNKNOWN -> pending++;
            }
        }

        batch.updateSummary(successes, failures, pending);
    }

    private BulkTransferItem locked(UUID itemId) {
        return items.findByIdForUpdate(itemId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Bulk item not found: " + itemId
                        )
                );
    }
}