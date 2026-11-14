package com.fiscalmotion.ledger.controller;

import com.fiscalmotion.ledger.dto.BulkTransferRequest;
import com.fiscalmotion.ledger.dto.BulkTransferResponse;
import com.fiscalmotion.ledger.service.BulkTransferService;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ledger/transfers")
@ConditionalOnProperty(
        prefix = "fiscalmotion.bulk",
        name = "enabled",
        havingValue = "true"
)
public class BulkTransferController {

    private final BulkTransferService service;

    public BulkTransferController(BulkTransferService service) {
        this.service = service;
    }

    @PostMapping("/bulk")
    public ResponseEntity<BulkTransferResponse> process(
            @RequestHeader("Batch-Id") String batchId,
            @Valid @RequestBody BulkTransferRequest request,
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        BulkTransferResponse response = service.process(
                batchId,
                request,
                authentication.getName()
        );

        return ResponseEntity.ok(response);
    }
}