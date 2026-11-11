package com.fiscalmotion.ledger.controller;

import com.fiscalmotion.ledger.dto.TransferRequest;
import com.fiscalmotion.ledger.model.Transaction;
import com.fiscalmotion.ledger.service.IdempotencyService;
import com.fiscalmotion.ledger.service.LedgerService;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;

    public LedgerController(LedgerService ledgerService, IdempotencyService idempotencyService) {
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/transfers")
    @RateLimiter(name = "ledgerTransferApi", fallbackMethod = "rateLimitFallback")
    public ResponseEntity<?> transferMoney(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Idempotency-Key header is required");
        }

        boolean acquired = idempotencyService.lockKey(idempotencyKey, Duration.ofMinutes(10));
        if (!acquired) {
            return idempotencyService.getResponse(idempotencyKey)
                    .map(response -> ResponseEntity.status(HttpStatus.OK).body(response))
                    .orElse(ResponseEntity.status(HttpStatus.CONFLICT)
                            .body("Duplicate or concurrent request detected for Idempotency-Key: " + idempotencyKey));
        }

        try {
            Transaction transaction = ledgerService.executeTransfer(
                    idempotencyKey,
                    request.sourceAccountId(),
                    request.targetAccountId(),
                    request.amount()
            );

            String responsePayload = String.format(
                    "{\"transactionId\":\"%s\",\"referenceId\":\"%s\",\"status\":\"%s\"}",
                    transaction.getId(),
                    transaction.getReferenceId(),
                    transaction.getStatus()
            );

            idempotencyService.saveResponse(idempotencyKey, responsePayload, Duration.ofMinutes(30));

            return ResponseEntity.status(HttpStatus.CREATED).body(transaction);
        } catch (Exception e) {
            idempotencyService.releaseKey(idempotencyKey);
            throw e;
        }
    }

    public ResponseEntity<String> rateLimitFallback(String idempotencyKey, TransferRequest request, RequestNotPermitted ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body("Rate limit exceeded. FiscalMotion limits transaction requests to 5 per second.");
    }
}