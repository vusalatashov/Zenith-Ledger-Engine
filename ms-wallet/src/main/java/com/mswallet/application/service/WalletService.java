package com.mswallet.application.service;

import com.mswallet.api.dto.request.CreateWalletRequest;
import com.mswallet.api.dto.request.TransferRequest;
import com.mswallet.api.dto.response.WalletResponse;
import com.mswallet.domain.model.Wallet;
import com.mswallet.domain.model.TransactionType;
import com.mswallet.infrastructure.exception.BusinessException;
import com.mswallet.infrastructure.persistence.entity.IdempotentRequestEntity;
import com.mswallet.infrastructure.persistence.entity.TransactionEntity;
import com.mswallet.infrastructure.persistence.entity.WalletEntity;
import com.mswallet.infrastructure.persistence.mapper.WalletMapper;
import com.mswallet.infrastructure.persistence.repository.IdempotencyRepository;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Wallet Service
 * * DESIGN PRINCIPLES:
 * 1. Atomicity: All database operations are wrapped in transactions.
 * 2. Resilience: Failed concurrent updates are automatically retried.
 * 3. Thread-Safety: Pessimistic locking prevents "Double Spending".
 * 4. Integrity: Every balance change is backed by an immutable Ledger (Transaction History).
 * 5. Idempotency: Prevents duplicate processing of the same request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final WalletMapper walletMapper;

    /**
     * Creates a new wallet.
     * @Transactional: Ensures that if the wallet is saved but the initial setup fails,
     * the database remains clean (Rollback).
     */
    @Transactional(rollbackFor = Exception.class)
    public WalletResponse create(CreateWalletRequest request) {
        log.info("Creating new wallet for user: {}", request.userId());

        // We use the Domain Model to represent business rules, decoupling it from DB logic.
        Wallet wallet = new Wallet(null, request.userId(), BigDecimal.ZERO, request.currency());
        WalletEntity savedEntity = walletRepository.save(walletMapper.toEntity(wallet));

        log.info("Wallet created successfully with ID: {}", savedEntity.getId());
        return walletMapper.toDto(walletMapper.toDomain(savedEntity));
    }

    /**
     * @Transactional: Mandatory for financial updates to ensure Atomicity.
     * Pessimistic Lock (via findAndLockWallet): Prevents other threads from reading/writing
     * this specific wallet until this method finishes. This stops "Race Conditions".
     */
    @Transactional(rollbackFor = Exception.class)
    public WalletResponse withdrawMoney(UUID walletId, BigDecimal amount, String idempotencyKey) {
        validateIdempotency(idempotencyKey);
        validateAmount(amount);

        // LOCK: 'SELECT FOR UPDATE' is triggered here at the DB level.
        WalletEntity entity = findAndLockWallet(walletId);
        Wallet wallet = walletMapper.toDomain(entity);

        try {
            wallet.withdraw(amount); // Business logic inside Domain Model
            entity.setBalance(wallet.getBalance());
            walletRepository.save(entity);

            // Audit Trail: Every withdrawal must have a corresponding record.
            createTransactionRecord(walletId, amount.negate(), TransactionType.WITHDRAW, "Manual withdrawal");

            saveIdempotencyKey(idempotencyKey);
        } catch (RuntimeException e) {
            throw new BusinessException(e.getMessage(), "INSUFFICIENT_BALANCE", HttpStatus.BAD_REQUEST);
        }

        return walletMapper.toDto(wallet);
    }

    @Transactional(rollbackFor = Exception.class)
    public WalletResponse depositMoney(UUID walletId, BigDecimal amount, String idempotencyKey) {
        validateIdempotency(idempotencyKey);
        validateAmount(amount);

        WalletEntity entity = findAndLockWallet(walletId);
        Wallet wallet = walletMapper.toDomain(entity);

        wallet.deposit(amount);
        entity.setBalance(wallet.getBalance());
        walletRepository.save(entity);

        createTransactionRecord(walletId, amount, TransactionType.DEPOSIT, "Manual deposit");

        saveIdempotencyKey(idempotencyKey);

        return walletMapper.toDto(wallet);
    }

    /**
     * @Retryable: If an OptimisticLocking failure occurs (two people updating at the same microsecond),
     * Spring will automatically restart this method up to 3 times.
     * * Deadlock Prevention: We sort UUIDs to always lock resources in the same order.
     * If Thread A locks (ID: 1 then 2) and Thread B locks (ID: 1 then 2), they never block each other
     * in a circular way.
     */
    @Retryable(
            retryFor = {ObjectOptimisticLockingFailureException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 500)
    )
    @Transactional(rollbackFor = Exception.class)
    public void transferMoney(TransferRequest request, String idempotencyKey) {
        validateIdempotency(idempotencyKey);

        if (request.fromWalletId().equals(request.toWalletId())) {
            throw new BusinessException("Self-transfer is blocked", "SAME_WALLET", HttpStatus.BAD_REQUEST);
        }
        validateAmount(request.amount());

        // KEY SENIOR LOGIC: Sorting IDs to prevent circular wait (Deadlock).
        var sortedIds = Stream.of(request.fromWalletId(), request.toWalletId()).sorted().toList();
        var walletMap = walletRepository.findAllById(sortedIds).stream()
                .collect(Collectors.toMap(WalletEntity::getId, Function.identity()));

        WalletEntity fromEntity = walletMap.get(request.fromWalletId());
        WalletEntity toEntity = walletMap.get(request.toWalletId());

        validateWalletsExistence(fromEntity, toEntity);
        validateCurrencyMatch(fromEntity, toEntity);

        Wallet fromDomain = walletMapper.toDomain(fromEntity);
        Wallet toDomain = walletMapper.toDomain(toEntity);

        try {
            // Updating Domain Models
            fromDomain.withdraw(request.amount());
            toDomain.deposit(request.amount());

            // Syncing changes back to Entities (Database persistence)
            fromEntity.setBalance(fromDomain.getBalance());
            toEntity.setBalance(toDomain.getBalance());

            // DOUBLE-ENTRY BOOKKEEPING: One record for 'money out', one for 'money in'.
            createTransactionRecord(fromEntity.getId(), request.amount().negate(),
                    TransactionType.TRANSFER_OUT, "Transfer to: " + toEntity.getId());
            createTransactionRecord(toEntity.getId(), request.amount(),
                    TransactionType.TRANSFER_IN, "Transfer from: " + fromEntity.getId());

            saveIdempotencyKey(idempotencyKey);
            log.info("Successfully transferred {} from {} to {}", request.amount(), fromEntity.getId(), toEntity.getId());
        } catch (Exception e) {
            log.error("Transfer failed: {}", e.getMessage());
            throw new BusinessException(e.getMessage(), "TRANSFER_FAILED", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * readOnly = true: Optimizes database performance by telling Hibernate
     * it doesn't need to check for changes (Dirty Checking) during this call.
     */
    @Transactional(readOnly = true)
    public List<TransactionEntity> getTransactionHistory(UUID walletId) {
        return transactionRepository.findAllByWalletIdOrderByCreatedAtDesc(walletId);
    }

    // --- Private Infrastructure Helpers ---

    /**
     * Ensures the request has not been processed before.
     */
    private void validateIdempotency(String idempotencyKey) {
        if (idempotencyKey != null && idempotencyRepository.existsById(idempotencyKey)) {
            log.warn("Duplicate request detected for key: {}", idempotencyKey);
            throw new BusinessException("This request has already been processed", "DUPLICATE_REQUEST", HttpStatus.CONFLICT);
        }
    }

    /**
     * Records the idempotency key to prevent future duplicate processing.
     */
    private void saveIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) return;
        IdempotentRequestEntity record = IdempotentRequestEntity.builder()
                .requestKey(idempotencyKey)
                .status("SUCCESS")
                .createdAt(LocalDateTime.now())
                .build();
        idempotencyRepository.save(record);
    }

    /**
     * findWithLockById: Executes 'SELECT ... FOR UPDATE'.
     * This is a Pessimistic Lock that ensures no other process touches this wallet
     * until the current transaction is committed or rolled back.
     */
    private WalletEntity findAndLockWallet(UUID id) {
        return walletRepository.findWithLockById(id)
                .orElseThrow(() -> new BusinessException("Wallet not found", "WALLET_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Invalid amount", "INVALID_AMOUNT", HttpStatus.BAD_REQUEST);
        }
    }

    private void validateWalletsExistence(WalletEntity from, WalletEntity to) {
        if (from == null || to == null) {
            throw new BusinessException("Wallet data missing", "WALLET_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
    }

    private void validateCurrencyMatch(WalletEntity from, WalletEntity to) {
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new BusinessException("Currency mismatch", "CURRENCY_MISMATCH", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Ledger Entry: This is the source of truth for all financial audits.
     */
    private void createTransactionRecord(UUID walletId, BigDecimal amount, TransactionType type, String description) {
        TransactionEntity tx = TransactionEntity.builder()
                .walletId(walletId)
                .amount(amount)
                .type(type)
                .description(description)
                .build();
        transactionRepository.save(tx);
    }
}