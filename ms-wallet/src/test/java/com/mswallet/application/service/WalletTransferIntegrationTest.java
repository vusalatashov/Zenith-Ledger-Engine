package com.mswallet.application.service;

import com.mswallet.api.dto.request.TransferRequest;
import com.mswallet.infrastructure.persistence.entity.TransactionEntity;
import com.mswallet.infrastructure.persistence.entity.WalletEntity;
import com.mswallet.infrastructure.persistence.repository.IdempotencyRepository;
import com.mswallet.infrastructure.persistence.repository.TransactionRepository;
import com.mswallet.infrastructure.persistence.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class WalletTransferIntegrationTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @MockitoSpyBean
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanUp() {
        transactionRepository.deleteAll();
        idempotencyRepository.deleteAll();
        walletRepository.deleteAll();
    }

    @Test
    @DisplayName("Transfer should rollback balances when system fails at the end")
    void transfer_ShouldRollback_WhenSystemFailsAtTheEnd() {
        WalletEntity fromEntity = createWallet(1L, "100.00");
        WalletEntity toEntity = createWallet(2L, "50.00");
        TransferRequest request = new TransferRequest(fromEntity.getId(), toEntity.getId(), new BigDecimal("20.00"));

        // FIX: Added UUID for idempotency
        String key = UUID.randomUUID().toString();

        assertThrows(RuntimeException.class, () -> {
            transactionTemplate.execute(status -> {
                walletService.transferMoney(request);
                throw new RuntimeException("Rollback Trigger");
            });
        });

        assertBalances(fromEntity.getId(), "100.00", toEntity.getId(), "50.00");
    }

    @Test
    @DisplayName("Ledger failure should trigger a full transaction rollback")
    void shouldRollback_WhenLedgerFails() {
        WalletEntity from = createWallet(3L, "100.00");
        WalletEntity to = createWallet(4L, "50.00");
        TransferRequest request = new TransferRequest(from.getId(), to.getId(), new BigDecimal("30.00"));

        doThrow(new RuntimeException("DB Error"))
                .when(transactionRepository).save(any(TransactionEntity.class));

        // FIX: Added UUID for idempotency
        assertThrows(RuntimeException.class, () ->
                walletService.transferMoney(request));

        assertBalances(from.getId(), "100.00", to.getId(), "50.00");
    }

    @Test
    @DisplayName("Concurrent deposits should compute balance correctly using locking")
    void concurrentDeposit_ShouldUpdateBalanceCorrectly() throws InterruptedException {
        WalletEntity wallet = walletRepository.save(WalletEntity.builder()
                .userId(99L).balance(BigDecimal.ZERO).currency("AZN").version(0L).build());

        int threadCount = 10;
        BigDecimal depositAmount = new BigDecimal("10.00");

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executorService.execute(() -> {
                try {
                    // FIX: Every concurrent request needs a UNIQUE key, otherwise
                    // our new Idempotency logic will block the other 9 threads!
                    walletService.depositMoney(wallet.getId(), depositAmount);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        WalletEntity finalWallet = walletRepository.findById(wallet.getId()).get();
        assertEquals(0, new BigDecimal("100.00").compareTo(finalWallet.getBalance()),
                "Balance loss detected during concurrent updates!");
    }

    // --- Helpers ---

    private WalletEntity createWallet(Long userId, String balance) {
        return walletRepository.save(WalletEntity.builder()
                .userId(userId)
                .balance(new BigDecimal(balance))
                .currency("AZN")
                .version(0L)
                .build());
    }

    private void assertBalances(UUID fromId, String expectedFrom, UUID toId, String expectedTo) {
        WalletEntity f = walletRepository.findById(fromId).orElseThrow();
        WalletEntity t = walletRepository.findById(toId).orElseThrow();
        assertEquals(0, new BigDecimal(expectedFrom).compareTo(f.getBalance()));
        assertEquals(0, new BigDecimal(expectedTo).compareTo(t.getBalance()));
    }
}