package com.mswallet.application.service;

import com.mswallet.api.dto.request.CreateWalletRequest;
import com.mswallet.api.dto.request.TransferRequest;
import com.mswallet.api.dto.response.WalletResponse;
import com.mswallet.domain.model.Wallet;
import com.mswallet.domain.model.TransactionType;
import com.mswallet.infrastructure.exception.BusinessException;
import com.mswallet.infrastructure.persistence.entity.TransactionEntity;
import com.mswallet.infrastructure.persistence.entity.WalletEntity;
import com.mswallet.infrastructure.persistence.mapper.WalletMapper;
import com.mswallet.infrastructure.persistence.repository.TransactionRepository;
import com.mswallet.infrastructure.persistence.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Core Wallet Service.
 * Free from infrastructure concerns (like Redis or HTTP headers).
 * Relies on Domain logic for state changes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final WalletMapper walletMapper;

    @Transactional
    public WalletResponse create(CreateWalletRequest request) {
        log.info("Creating wallet for user: {}", request.userId());
        Wallet wallet = new Wallet(null, request.userId(), BigDecimal.ZERO, request.currency());
        WalletEntity savedEntity = walletRepository.save(walletMapper.toEntity(wallet));
        return walletMapper.toDto(walletMapper.toDomain(savedEntity));
    }

    @Transactional
    public WalletResponse withdrawMoney(UUID walletId, BigDecimal amount) {
        WalletEntity entity = fetchWallet(walletId);
        Wallet wallet = walletMapper.toDomain(entity);

        wallet.withdraw(amount); // Domain logic validation

        entity.setBalance(wallet.getBalance());
        walletRepository.save(entity);
        recordTransaction(walletId, amount.negate(), TransactionType.WITHDRAW, "Withdrawal processed");

        return walletMapper.toDto(wallet);
    }

    @Transactional
    public WalletResponse depositMoney(UUID walletId, BigDecimal amount) {
        WalletEntity entity = fetchWallet(walletId);
        Wallet wallet = walletMapper.toDomain(entity);

        wallet.deposit(amount);

        entity.setBalance(wallet.getBalance());
        walletRepository.save(entity);
        recordTransaction(walletId, amount, TransactionType.DEPOSIT, "Deposit processed");

        return walletMapper.toDto(wallet);
    }

    /**
     * Executes transfer. Retries automatically if another transaction
     * modifies the wallet simultaneously (Optimistic Lock failure).
     */
    @Retryable(
            retryFor = {ObjectOptimisticLockingFailureException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 200, multiplier = 2)
    )
    @Transactional
    public void transferMoney(TransferRequest request) {
        if (request.fromWalletId().equals(request.toWalletId())) {
            throw new BusinessException("Cannot transfer to the same wallet", "SAME_WALLET_TRANSFER", HttpStatus.BAD_REQUEST);
        }

        // Prevent deadlocks by always locking in a deterministic order
        var sortedIds = Stream.of(request.fromWalletId(), request.toWalletId()).sorted().toList();
        var walletMap = walletRepository.findAllById(sortedIds).stream()
                .collect(Collectors.toMap(WalletEntity::getId, Function.identity()));

        WalletEntity fromEntity = walletMap.get(request.fromWalletId());
        WalletEntity toEntity = walletMap.get(request.toWalletId());

        validateWalletsForTransfer(fromEntity, toEntity);

        Wallet fromDomain = walletMapper.toDomain(fromEntity);
        Wallet toDomain = walletMapper.toDomain(toEntity);

        fromDomain.withdraw(request.amount());
        toDomain.deposit(request.amount());

        fromEntity.setBalance(fromDomain.getBalance());
        toEntity.setBalance(toDomain.getBalance());

        recordTransaction(fromEntity.getId(), request.amount().negate(), TransactionType.TRANSFER_OUT, "To: " + toEntity.getId());
        recordTransaction(toEntity.getId(), request.amount(), TransactionType.TRANSFER_IN, "From: " + fromEntity.getId());
    }

    private WalletEntity fetchWallet(UUID id) {
        return walletRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Wallet not found", "WALLET_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private void validateWalletsForTransfer(WalletEntity from, WalletEntity to) {
        if (from == null || to == null) {
            throw new BusinessException("One or both wallets not found", "WALLET_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new BusinessException("Currency mismatch", "CURRENCY_MISMATCH", HttpStatus.BAD_REQUEST);
        }
    }

    private void recordTransaction(UUID walletId, BigDecimal amount, TransactionType type, String description) {
        transactionRepository.save(TransactionEntity.builder()
                .walletId(walletId)
                .amount(amount)
                .type(type)
                .description(description)
                .build());
    }
}