
package com.fiscalmotion.ledger.validation;

import com.fiscalmotion.ledger.exception.InvalidTransferException;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyValidator {

    private static final int SCALE = 4;
    private static final int PRECISION = 19;

    private MoneyValidator() {
    }

    public static BigDecimal requirePositive(BigDecimal amount) {

        if (amount == null || amount.signum() <= 0) {
            throw new InvalidTransferException(
                    "Amount must be greater than zero"
            );
        }

        final BigDecimal normalized;

        try {
            normalized = amount.setScale(
                    SCALE,
                    RoundingMode.UNNECESSARY
            );
        } catch (ArithmeticException exception) {
            throw new InvalidTransferException(
                    "Amount cannot have more than 4 non-zero decimal places"
            );
        }

        if (normalized.precision() > PRECISION) {
            throw new InvalidTransferException(
                    "Amount exceeds supported monetary precision"
            );
        }

        return normalized;
    }
}
