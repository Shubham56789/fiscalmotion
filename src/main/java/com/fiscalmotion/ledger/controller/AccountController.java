
package com.fiscalmotion.ledger.controller;

import com.fiscalmotion.ledger.model.Account;
import com.fiscalmotion.ledger.model.BalanceAdjustment;
import com.fiscalmotion.ledger.service.AccountManagementService;

import com.fiscalmotion.ledger.service.BalanceAdjustmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountManagementService accountService;
    private final BalanceAdjustmentService balanceAdjustmentService;

    public AccountController(
            AccountManagementService accountService,
            BalanceAdjustmentService balanceAdjustmentService
    ) {
        this.accountService = accountService;
        this.balanceAdjustmentService = balanceAdjustmentService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            @Valid @RequestBody CreateAccountRequest request
    ) {
        Account account = accountService.createAccount(
                request.accountNumber(),
                request.accountHolderName()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(toResponse(account));
    }

    @GetMapping
    public List<AccountResponse> getAccounts() {
        return accountService.getAllAccounts()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(
            @PathVariable UUID id
    ) {
        return toResponse(
                accountService.getAccount(id)
        );
    }

    private AccountResponse toResponse(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getAccountHolderName(),
                account.getCurrency(),
                account.getBalance(),
                account.getVersion(),
                account.getCreatedAt()
        );
    }

    public record CreateAccountRequest(
            @NotBlank
            @Pattern(
                    regexp = "^[A-Za-z0-9-]{4,40}$",
                    message = "Account number must contain 4-40 letters, digits or hyphens"
            )
            String accountNumber,

            @NotBlank
            @Size(max = 120)
            String accountHolderName
    ) {}

    public record AccountResponse(
            UUID id,
            String accountNumber,
            String accountHolderName,
            String currency,
            BigDecimal balance,
            Long version,
            Instant createdAt
    ) {}


    @PostMapping("/{id}/adjustments")
    public ResponseEntity<BalanceAdjustment> adjustBalance(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BalanceAdjustmentRequest request,
            java.security.Principal principal
    ) {
        BalanceAdjustment result =
                balanceAdjustmentService.adjustBalance(
                        id,
                        request.targetBalance(),
                        request.expectedVersion(),
                        request.reason(),
                        principal.getName(),
                        idempotencyKey
                );

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/adjustments")
    public List<BalanceAdjustment> getAdjustmentHistory(
            @PathVariable UUID id
    ) {
        accountService.getAccount(id);
        return balanceAdjustmentService.getHistory(id);
    }

    public record BalanceAdjustmentRequest(
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.DecimalMin("0.00")
            BigDecimal targetBalance,

            @jakarta.validation.constraints.NotNull
            Long expectedVersion,

            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 500)
            String reason
    ) {}

}
