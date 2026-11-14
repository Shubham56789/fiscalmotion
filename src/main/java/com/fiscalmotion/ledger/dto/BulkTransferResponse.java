package com.fiscalmotion.ledger.dto;

import java.util.List;
import java.util.UUID;

public record BulkTransferResponse(
        String batchId,
        int totalCount,
        int successCount,
        int failureCount,
        int pendingCount,
        List<RowResult> results
) {

    public record RowResult(
            int rowNumber,
            String status,
            UUID transactionId,
            String message
    ) {
    }
}