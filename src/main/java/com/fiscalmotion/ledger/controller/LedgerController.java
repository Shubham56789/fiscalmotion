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

        boolean acquired = idempotencyService.lockKey(idempotencyKey, Duration.ofMinutes(10));
        if (!acquired) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Concurrent or duplicate request detected for Idempotency-Key: " + idempotencyKey);
        }

        try {
            Transaction transaction = ledgerService.executeTransfer(
                    idempotencyKey,
                    request.sourceAccountId(),
                    request.targetAccountId(),
                    request.amount()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(transaction);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (Exception e) {
            idempotencyService.releaseKey(idempotencyKey);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // Fallback method triggered when rate limit is exceeded
    public ResponseEntity<String> rateLimitFallback(String idempotencyKey, TransferRequest request, RequestNotPermitted ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body("Rate limit exceeded. FiscalMotion limits transaction requests to 5 per second.");
    }
}