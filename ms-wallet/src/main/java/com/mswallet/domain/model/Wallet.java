package com.mswallet.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

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
            throw new RuntimeException("Insufficient funds for wallet: " + id);
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

    public UUID getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getCurrency() {
        return currency;
    }
}