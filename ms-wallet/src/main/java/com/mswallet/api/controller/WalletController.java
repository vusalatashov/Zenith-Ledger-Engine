package com.mswallet.api.controller;

import com.mswallet.api.dto.request.CreateWalletRequest;
import com.mswallet.api.dto.request.TransferRequest;
import com.mswallet.api.dto.response.WalletResponse;
import com.mswallet.application.service.WalletService;
import com.mswallet.common.idempotency.Idempotent;
import com.mswallet.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WalletResponse> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        return ApiResponse.success(walletService.create(request), "Wallet created successfully");
    }

    @PatchMapping("/{id}/withdraw")
    @Idempotent
    public ApiResponse<WalletResponse> withdraw(@PathVariable UUID id, @RequestParam BigDecimal amount) {
        return ApiResponse.success(walletService.withdrawMoney(id, amount), "Withdrawal successful");
    }

    @PatchMapping("/{id}/deposit")
    @Idempotent
    public ApiResponse<WalletResponse> deposit(@PathVariable UUID id, @RequestParam BigDecimal amount) {
        return ApiResponse.success(walletService.depositMoney(id, amount), "Deposit successful");
    }

    @PostMapping("/transfer")
    @Idempotent
    public ApiResponse<Void> transfer(@Valid @RequestBody TransferRequest request) {
        walletService.transferMoney(request);
        return ApiResponse.success(null, "Transfer completed successfully");
    }
}