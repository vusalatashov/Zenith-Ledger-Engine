package com.mswallet.api.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record WalletResponse(
    UUID id,
    Long userId,
    BigDecimal balance,
    String currency
) {}