package com.fiscalmotion.ledger.service;

import com.fiscalmotion.ledger.dto.BulkTransferRequest;
import com.fiscalmotion.ledger.dto.BulkTransferResponse;
import com.fiscalmotion.ledger.exception.*;
import com.fiscalmotion.ledger.model.*;
import com.fiscalmotion.ledger.repository.AccountRepository;
import com.fiscalmotion.ledger.repository.TransactionRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class BulkTransferService {

    private final BulkTransferPersistenceService persistence;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final LedgerService ledger;
    private final BulkTransferReconciliationService reconciliation;

    public BulkTransferService(
            BulkTransferPersistenceService persistence,
            AccountRepository accounts,
            TransactionRepository transactions,
            LedgerService ledger,
            BulkTransferReconciliationService reconciliation
    ) {
        this.persistence = persistence;
        this.accounts = accounts;
        this.transactions = transactions;
        this.ledger = ledger;
        this.reconciliation = reconciliation;
    }

    public BulkTransferResponse process(
            String batchId,
            BulkTransferRequest request,
            String submittedBy
    ) {
        validateBatch(batchId, request);

        if (submittedBy == null || submittedBy.isBlank()) {
            throw new IllegalArgumentException(
                    "Authenticated administrator is required"
            );
        }

        String fingerprint = fingerprint(request);

        registerOrVerifyBatch(
                batchId,
                fingerprint,
                request,
                submittedBy
        );

        List<BulkTransferItem> rows =
                persistence.getItems(batchId);

        for (BulkTransferItem row : rows) {

            if (row.getStatus() != BulkTransferItem.Status.PENDING) {
                continue;
            }

            if (persistence.claim(row.getId())) {
                executeRow(row);
            }
        }

        reconciliation.reconcile(batchId);

        persistence.refreshBatchStatus(batchId);

        return buildResponse(batchId);
    }

    private void validateBatch(
            String batchId,
            BulkTransferRequest request
    ) {
        if (batchId == null
                || !batchId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException(
                    "Invalid Batch-Id"
            );
        }

        if (request == null
                || request.transfers() == null
                || request.transfers().isEmpty()) {
            throw new IllegalArgumentException(
                    "Batch must contain at least one transfer"
            );
        }

        if (request.transfers().size() > 500) {
            throw new IllegalArgumentException(
                    "Maximum 500 transfers per batch"
            );
        }

        for (var row : request.transfers()) {
            if (row == null) {
                throw new IllegalArgumentException(
                        "Transfer row cannot be null"
                );
            }

            if (row.sourceAccount() != null
                    && row.sourceAccount().length() > 255) {
                throw new IllegalArgumentException(
                        "Source account exceeds storage limit"
                );
            }

            if (row.targetAccount() != null
                    && row.targetAccount().length() > 255) {
                throw new IllegalArgumentException(
                        "Target account exceeds storage limit"
                );
            }

            if (row.amount() != null
                    && row.amount().toString().length() > 1000) {
                throw new IllegalArgumentException(
                        "Amount exceeds storage limit"
                );
            }
        }
    }

    private void registerOrVerifyBatch(
            String batchId,
            String fingerprint,
            BulkTransferRequest request,
            String submittedBy
    ) {
        BulkTransferBatch existing = findBatchOrNull(batchId);

        if (existing == null) {
            try {
                persistence.createBatch(
                        batchId,
                        fingerprint,
                        request,
                        submittedBy
                );
            } catch (DataIntegrityViolationException ex) {
                // Another request may have created this batch.
                // Verify the persisted batch before proceeding.
            }

            existing = persistence.getBatch(batchId);
        }

        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw new ResourceConflictException(
                    "Batch-Id already used for different transfer data"
            );
        }

        if (!existing.getSubmittedBy().equals(submittedBy)) {
            throw new ResourceConflictException(
                    "Batch belongs to another administrator"
            );
        }
    }

    private BulkTransferBatch findBatchOrNull(String batchId) {
        try {
            return persistence.getBatch(batchId);
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    private void executeRow(BulkTransferItem row) {

        UUID itemId = row.getId();

        try {
            var existing =
                    transactions.findByReferenceId(row.getReferenceId());

            if (existing.isPresent()) {
                persistence.markSuccess(
                        itemId,
                        existing.get().getId()
                );
                return;
            }

            String source = row.getSourceAccount();
            String target = row.getTargetAccount();
            BigDecimal amount = row.getAmount();

            validateRow(source, target, amount);

            Account sourceAccount =
                    accounts.findByAccountNumber(source)
                            .orElseThrow(() ->
                                    new AccountNotFoundException(
                                            "Source account not found"
                                    )
                            );

            Account targetAccount =
                    accounts.findByAccountNumber(target)
                            .orElseThrow(() ->
                                    new AccountNotFoundException(
                                            "Target account not found"
                                    )
                            );

            Transaction transaction = ledger.executeTransfer(
                    row.getReferenceId(),
                    sourceAccount.getId(),
                    targetAccount.getId(),
                    amount
            );

            persistence.markSuccess(
                    itemId,
                    transaction.getId()
            );

        } catch (
                AccountNotFoundException |
                InsufficientBalanceException |
                InvalidTransferException ex
        ) {
            persistence.markFailed(
                    itemId,
                    ex.getMessage()
            );

        } catch (Exception ex) {
            // A ledger commit may have succeeded even if
            // the caller received an exception.
            // Do not execute the transfer again.
            persistence.markUnknown(
                    itemId,
                    "Transfer outcome uncertain; reconciliation required"
            );
        }
    }

    private void validateRow(
            String source,
            String target,
            BigDecimal amount
    ) {
        if (source == null || source.isBlank()) {
            throw new InvalidTransferException(
                    "Source account is required"
            );
        }

        if (target == null || target.isBlank()) {
            throw new InvalidTransferException(
                    "Target account is required"
            );
        }

        if (source.equals(target)) {
            throw new InvalidTransferException(
                    "Source and target must differ"
            );
        }

        if (amount == null
                || amount.signum() <= 0
                || amount.scale() < 0
                || amount.scale() > 4
                || amount.precision() - amount.scale() > 15) {
            throw new InvalidTransferException(
                    "Amount must be positive with at most "
                            + "15 integer and 4 decimal digits"
            );
        }
    }

    private BulkTransferResponse buildResponse(String batchId) {

        List<BulkTransferItem> rows =
                persistence.getItems(batchId);

        int success = 0;
        int failure = 0;
        int pending = 0;

        List<BulkTransferResponse.RowResult> results =
                new ArrayList<>();

        for (BulkTransferItem row : rows) {

            switch (row.getStatus()) {
                case SUCCESS -> success++;
                case FAILED -> failure++;
                case PENDING, PROCESSING, UNKNOWN -> pending++;
            }

            results.add(
                    new BulkTransferResponse.RowResult(
                            row.getRowNumber(),
                            row.getStatus().name(),
                            row.getTransactionId(),
                            row.getResultMessage()
                    )
            );
        }

        return new BulkTransferResponse(
                batchId,
                rows.size(),
                success,
                failure,
                pending,
                results
        );
    }

    private String fingerprint(BulkTransferRequest request) {

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            addInt(digest, request.transfers().size());

            for (var row : request.transfers()) {

                addNullableString(digest, row.sourceAccount());
                addNullableString(digest, row.targetAccount());

                if (row.amount() == null) {
                    addInt(digest, -1);
                } else {
                    addString(
                            digest,
                            row.amount()
                                    .stripTrailingZeros()
                                    .toPlainString()
                    );
                }
            }

            return HexFormat.of().formatHex(digest.digest());

        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(
                    "SHA-256 unavailable",
                    ex
            );
        }
    }

    private void addNullableString(
            MessageDigest digest,
            String value
    ) {
        if (value == null) {
            addInt(digest, -1);
        } else {
            addString(digest, value.trim());
        }
    }

    private void addString(
            MessageDigest digest,
            String value
    ) {
        byte[] bytes =
                value.getBytes(StandardCharsets.UTF_8);

        addInt(digest, bytes.length);
        digest.update(bytes);
    }

    private void addInt(
            MessageDigest digest,
            int value
    ) {
        digest.update(
                ByteBuffer.allocate(Integer.BYTES)
                        .putInt(value)
                        .array()
        );
    }
}