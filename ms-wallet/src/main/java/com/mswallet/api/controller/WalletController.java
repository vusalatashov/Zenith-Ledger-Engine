package com.mswallet.api.controller;

import com.mswallet.api.dto.request.CreateWalletRequest;
import com.mswallet.api.dto.request.TransferRequest;
import com.mswallet.api.dto.response.WalletResponse;
import com.mswallet.application.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Wallet Controller
 * Provides endpoints for wallet management and financial transactions.
 * Implements Idempotency support via 'X-Idempotency-Key' header.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    /**
     * Creates a new wallet for the specified user.
     */
    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        log.info("Request to create wallet for user: {}", request.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(walletService.create(request));
    }

    /**
     * Withdraws money from a wallet.
     * @param idempotencyKey Required to prevent duplicate withdrawals in case of network retries.
     */
    @PatchMapping("/{id}/withdraw")
    public ResponseEntity<WalletResponse> withdraw(
            @PathVariable UUID id,
            @RequestParam BigDecimal amount,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        log.info("Withdrawal request: Wallet {}, Amount {}, Key {}", id, amount, idempotencyKey);
        return ResponseEntity.ok(walletService.withdrawMoney(id, amount, idempotencyKey));
    }

    /**
     * Deposits money into a wallet.
     */
    @PatchMapping("/{id}/deposit")
    public ResponseEntity<WalletResponse> deposit(
            @PathVariable UUID id,
            @RequestParam BigDecimal amount,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        log.info("Deposit request: Wallet {}, Amount {}, Key {}", id, amount, idempotencyKey);
        return ResponseEntity.ok(walletService.depositMoney(id, amount, idempotencyKey));
    }

    /**
     * Performs a money transfer between two wallets.
     * @param idempotencyKey Critical for transfers to ensure the same transfer doesn't execute twice.
     */
    @PostMapping("/transfer")
    public ResponseEntity<String> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        log.info("Transfer request from {} to {}, Amount {}, Key {}",
                request.fromWalletId(), request.toWalletId(), request.amount(), idempotencyKey);

        walletService.transferMoney(request, idempotencyKey);

        return ResponseEntity.ok("Transfer completed successfully");
    }
}