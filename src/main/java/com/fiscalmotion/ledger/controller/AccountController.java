package com.fiscalmotion.ledger.controller;

import com.fiscalmotion.ledger.model.Account;
import com.fiscalmotion.ledger.repository.AccountRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountRepository accountRepository;

    public AccountController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @PostMapping
    public ResponseEntity<Account> createAccount(@RequestBody Map<String, String> request) {
        String accountNumber = request.get("accountNumber");
        String currency = request.getOrDefault("currency", "INR");

        Account account = accountRepository.save(new Account(accountNumber, currency));
        return ResponseEntity.ok(account);
    }
}