
package com.fiscalmotion.ledger.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record TransactionCreatedEvent(
        UUID transactionId,
        String referenceId,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String status
) {
}
