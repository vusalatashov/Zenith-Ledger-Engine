package com.mswallet.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateWalletRequest(
        @NotNull(message = "User ID is required")
        Long userId,

        @NotBlank(message = "Currency code is required")
        @Size(min = 3, max = 3, message = "Currency code must be exactly 3 characters (e.g., USD, AZN)")
        String currency
) {}