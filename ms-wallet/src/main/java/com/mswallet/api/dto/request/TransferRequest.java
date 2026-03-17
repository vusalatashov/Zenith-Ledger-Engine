package com.mswallet.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(
    @NotNull UUID fromWalletId,
    @NotNull UUID toWalletId,
    @NotNull @Positive BigDecimal amount
) {}