package com.mswallet.domain.model;

import com.mswallet.infrastructure.exception.BusinessException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Getter
public class Wallet {
    private final UUID id;
    private final Long userId;
    private BigDecimal balance;
    private final String currency;

    public Wallet(UUID id, Long userId, BigDecimal balance, String currency) {
        this.id = id;
        this.userId = userId;
        this.balance = (balance == null) ?
                BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP) :
                balance.setScale(4, RoundingMode.HALF_UP);
        this.currency = currency;
    }

    public void withdraw(BigDecimal amount) {
        validateAmount(amount);
        if (this.balance.compareTo(amount) < 0) {
            throw new BusinessException(
                    "Insufficient funds for wallet: " + id,
                    "INSUFFICIENT_BALANCE",
                    HttpStatus.BAD_REQUEST
            );
        }
        this.balance = this.balance.subtract(amount);
    }

    public void deposit(BigDecimal amount) {
        validateAmount(amount);
        this.balance = this.balance.add(amount);
    }


    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transaction amount must be positive");
        }
    }
}