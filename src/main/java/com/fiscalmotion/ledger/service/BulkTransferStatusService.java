
package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.dto.BulkTransferResponse;
import com.fiscalmotion.ledger.model.BulkTransferBatch;
import com.fiscalmotion.ledger.model.BulkTransferItem;
import com.fiscalmotion.ledger.repository.BulkTransferBatchRepository;
import com.fiscalmotion.ledger.repository.BulkTransferItemRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class BulkTransferStatusService {

    private final BulkTransferBatchRepository batches;
    private final BulkTransferItemRepository items;

    public BulkTransferStatusService(
            BulkTransferBatchRepository batches,
            BulkTransferItemRepository items
    ) {
        this.batches = batches;
        this.items = items;
    }

    @Transactional(readOnly = true)
    public BulkTransferResponse getStatus(
            String batchId,
            String requestedBy
    ) {
        if (batchId == null || batchId.isBlank()
                || batchId.length() > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid batch ID"
            );
        }

        BulkTransferBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Batch not found"
                ));

        // Do not disclose another administrator's
        // batch details.
        if (!batch.getSubmittedBy().equals(requestedBy)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Batch not found"
            );
        }

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

        List<BulkTransferResponse.RowResult> results =
                rows.stream()
                        .map(row ->
                                new BulkTransferResponse.RowResult(
                                        row.getRowNumber(),
                                        row.getStatus().name(),
                                        row.getTransactionId(),
                                        row.getResultMessage()
                                )
                        )
                        .toList();

        return new BulkTransferResponse(
                batch.getBatchId(),
                batch.getTotalCount(),
                successes,
                failures,
                pending,
                results
        );
    }
}
