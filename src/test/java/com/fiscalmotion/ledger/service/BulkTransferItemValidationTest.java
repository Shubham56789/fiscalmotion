package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.model.BulkTransferBatch;
import com.fiscalmotion.ledger.model.BulkTransferItem;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class BulkTransferItemValidationTest {

    private BulkTransferItem item(BigDecimal amount) {
        BulkTransferBatch batch = new BulkTransferBatch(
                "test-batch",
                "test-fingerprint",
                1,
                "testadmin"
        );

        return new BulkTransferItem(
                batch,
                2,
                "bulk:test-batch:0",
                "ACC001",
                "ACC002",
                amount
        );
    }

    @Test
    void shouldPreserveHighPrecisionAmount() {
        BigDecimal amount =
                new BigDecimal("123.12345678901234567890123456789");

        BulkTransferItem row = item(amount);

        assertEquals(amount, row.getAmount());
        assertEquals(amount.toString(), row.getAmountText());
    }

    @Test
    void shouldPreserveNegativeAmount() {
        BigDecimal amount = new BigDecimal("-100.12345");

        BulkTransferItem row = item(amount);

        assertEquals(amount, row.getAmount());
    }

    @Test
    void shouldAllowNullAmountForRowValidation() {
        BulkTransferItem row = item(null);

        assertNull(row.getAmount());
        assertNull(row.getAmountText());
    }

    @Test
    void shouldPreserveOriginalDecimalScale() {
        BigDecimal amount = new BigDecimal("100.00000");

        BulkTransferItem row = item(amount);

        assertEquals("100.00000", row.getAmountText());
        assertEquals(5, row.getAmount().scale());
    }
}