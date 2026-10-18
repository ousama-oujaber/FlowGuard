package com.flowguard.core.application;

import com.flowguard.core.application.port.in.SubmitMessageUseCase.SubmitCommand;
import com.flowguard.core.application.port.in.SubmitMessageUseCase.SubmitResult;
import com.flowguard.core.application.port.out.IdempotencyRepositoryPort;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.application.service.SubmitMessageService;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.value.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyConcurrencySimulationTest {

    @Test
    @DisplayName("High-concurrency race condition: 8 threads race on same idempotency key; exactly 1 message survives and all 8 get same messageId")
    void concurrentRaceOnIdempotencyKey() throws Exception {
        // In-memory atomic stores simulating PostgreSQL unique constraints
        Map<MessageId, IntegrationMessage> messageStore = new ConcurrentHashMap<>();
        Map<String, IdempotencyRepositoryPort.IdempotencyEntry> idempotencyStore = new ConcurrentHashMap<>();

        MessageRepositoryPort mockMessageRepo = new MessageRepositoryPort() {
            @Override
            public void save(IntegrationMessage message) {
                messageStore.put(message.getId(), message);
            }

            @Override
            public Optional<IntegrationMessage> findById(MessageId id) {
                return Optional.ofNullable(messageStore.get(id));
            }

            @Override
            public Optional<IntegrationMessage> findByIdAndClientId(MessageId id, ClientId clientId) {
                IntegrationMessage m = messageStore.get(id);
                if (m != null && m.getClientId().equals(clientId)) {
                    return Optional.of(m);
                }
                return Optional.empty();
            }

            @Override
            public List<IntegrationMessage> claimDueMessages(int batchSize, String workerId, LeaseToken token, Instant leaseUntil) {
                return Collections.emptyList();
            }

            @Override
            public boolean updateStatusWithLeaseFence(IntegrationMessage message, LeaseToken leaseToken) {
                return true;
            }

            @Override
            public long countByStatus(MessageStatus status) {
                return messageStore.values().stream().filter(m -> m.getStatus() == status).count();
            }

            @Override
            public void delete(MessageId id) {
                messageStore.remove(id);
            }

            @Override
            public List<IntegrationMessage> findExpiredLeases(Instant cutoff, int batchSize) {
                return Collections.emptyList();
            }
        };

        IdempotencyRepositoryPort mockIdempRepo = new IdempotencyRepositoryPort() {
            @Override
            public boolean save(IdempotencyEntry entry) {
                // Atomic putIfAbsent simulates DB unique index on (client_id, key_digest)
                String compositeKey = entry.clientId().value() + ":" + entry.keyDigest();
                return idempotencyStore.putIfAbsent(compositeKey, entry) == null;
            }

            @Override
            public Optional<IdempotencyEntry> find(ClientId clientId, String keyDigest) {
                return Optional.ofNullable(idempotencyStore.get(clientId.value() + ":" + keyDigest));
            }
        };

        SubmitMessageService service = new SubmitMessageService(mockMessageRepo, mockIdempRepo, 262144, 5, 7);

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<SubmitResult>> futures = new ArrayList<>();

        ClientId clientId = new ClientId("client_concurrent");
        MessageType messageType = new MessageType("ORDER_SUBMISSION");
        DestinationKey destinationKey = new DestinationKey("BILLING_GATEWAY");
        CorrelationId correlationId = new CorrelationId("corr-concurrent-1");
        IdempotencyKey idempotencyKey = new IdempotencyKey("idemp-concurrent-key-999");
        String payload = "{\"transaction\":999,\"amount\":150.00}";

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(); // Ensure all threads start simultaneously
                SubmitCommand command = new SubmitCommand(
                        clientId, messageType, "POS_TERMINAL", destinationKey, correlationId, "EXT-RACE", payload, idempotencyKey
                );
                return service.submit(command);
            }));
        }

        List<SubmitResult> results = new ArrayList<>();
        for (Future<SubmitResult> future : futures) {
            results.add(future.get(5, TimeUnit.SECONDS));
        }

        executor.shutdown();

        // 1. All threads must receive a non-null result
        assertThat(results).hasSize(threadCount);

        // 2. All 8 threads must receive the EXACT SAME messageId
        MessageId winningMessageId = results.get(0).messageId();
        assertThat(winningMessageId).isNotNull();
        for (SubmitResult result : results) {
            assertThat(result.messageId()).isEqualTo(winningMessageId);
            assertThat(result.status()).isEqualTo(MessageStatus.QUEUED);
        }

        // 3. Exactly one thread must have idempotentReplay = false, and 7 threads must have idempotentReplay = true
        long firstTimeCount = results.stream().filter(r -> !r.idempotentReplay()).count();
        long replayCount = results.stream().filter(SubmitResult::idempotentReplay).count();
        assertThat(firstTimeCount).isEqualTo(1);
        assertThat(replayCount).isEqualTo(threadCount - 1);

        // 4. Exactly one message must remain in the message store (orphans were cleaned up!)
        assertThat(messageStore).hasSize(1);
        assertThat(messageStore.containsKey(winningMessageId)).isTrue();
    }
}
