
package com.fiscalmotion.ledger.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountReconciliationResult(
        UUID accountId,
        String accountNumber,
        BigDecimal storedBalance,
        BigDecimal ledgerBalance,
        BigDecimal difference,
        long entryCount,
        Status status
) {
    public enum Status {
        MATCHED,
        MISMATCHED,
        INCOMPLETE_HISTORY
    }
}
