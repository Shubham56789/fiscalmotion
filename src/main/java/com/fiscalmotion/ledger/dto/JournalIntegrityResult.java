
package com.fiscalmotion.ledger.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record JournalIntegrityResult(
        UUID transactionId,
        String referenceId,
        boolean valid,
        int entryCount,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        List<String> violations
) {
}
