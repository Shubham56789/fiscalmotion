package com.fiscalmotion.ledger.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record BulkTransferRequest(
        @NotEmpty
        List<@NotNull Item> transfers
) {
    public record Item(
            String sourceAccount,
            String targetAccount,
            BigDecimal amount
    ) {
    }
}