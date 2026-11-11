package com.fiscalmotion.ledger.controller;

import com.fiscalmotion.ledger.model.Account;
import com.fiscalmotion.ledger.repository.AccountRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountRepository accountRepository;

    public AccountController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @PostMapping
    public ResponseEntity<Account> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = new Account(
                request.accountNumber(),
                request.currency(),
                request.initialBalance()
        );

        Account saved = accountRepository.save(account);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    public record CreateAccountRequest(
            @NotBlank(message = "accountNumber is required") String accountNumber,
            @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be 3 uppercase letters") String currency,
            @DecimalMin(value = "0.00", message = "initialBalance must be >= 0") BigDecimal initialBalance
    ) {
        public CreateAccountRequest {
            if (currency == null) {
                currency = "INR";
            }
        }
    }
}