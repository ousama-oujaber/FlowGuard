package com.flowguard.core.application;

import com.flowguard.core.application.port.in.BulkSubmitMessageUseCase;
import com.flowguard.core.application.port.in.BulkSubmitMessageUseCase.BulkSubmitCommand;
import com.flowguard.core.application.port.in.BulkSubmitMessageUseCase.BulkSubmitResult;
import com.flowguard.core.application.port.in.SubmitMessageUseCase;
import com.flowguard.core.application.port.in.SubmitMessageUseCase.SubmitCommand;
import com.flowguard.core.application.port.in.SubmitMessageUseCase.SubmitResult;
import com.flowguard.core.application.service.BulkSubmitMessageService;
import com.flowguard.core.domain.exception.BatchSizeExceededException;
import com.flowguard.core.domain.exception.EmptyBatchException;
import com.flowguard.core.domain.exception.IdempotencyConflictException;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.value.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BulkSubmitMessageServiceTest {

    private SubmitMessageUseCase submitMessageUseCase;
    private BulkSubmitMessageService bulkService;

    private final ClientId clientId = new ClientId("client_tenant_a");
    private final MessageType messageType = new MessageType("ORDER_SYNC");
    private final DestinationKey destinationKey = new DestinationKey("crm_orders");

    @BeforeEach
    void setUp() {
        submitMessageUseCase = mock(SubmitMessageUseCase.class);
        bulkService = new BulkSubmitMessageService(submitMessageUseCase);
    }

    @Test
    @DisplayName("Successfully submits a batch of distinct messages")
    void testSubmitBatchSuccess() {
        SubmitCommand cmd1 = createCommand("key-1", "{\"orderId\": 1}");
        SubmitCommand cmd2 = createCommand("key-2", "{\"orderId\": 2}");

        SubmitResult res1 = new SubmitResult(MessageId.generate(), MessageStatus.QUEUED, cmd1.correlationId(), Instant.now(), false);
        SubmitResult res2 = new SubmitResult(MessageId.generate(), MessageStatus.QUEUED, cmd2.correlationId(), Instant.now(), false);

        when(submitMessageUseCase.submit(cmd1)).thenReturn(res1);
        when(submitMessageUseCase.submit(cmd2)).thenReturn(res2);

        BulkSubmitResult batchResult = bulkService.submitBatch(new BulkSubmitCommand(clientId, List.of(cmd1, cmd2)));

        assertThat(batchResult.totalCount()).isEqualTo(2);
        assertThat(batchResult.acceptedCount()).isEqualTo(2);
        assertThat(batchResult.replayedCount()).isEqualTo(0);
        assertThat(batchResult.items()).hasSize(2);
        assertThat(batchResult.items().get(0).idempotencyKey()).isEqualTo("key-1");
        assertThat(batchResult.items().get(1).idempotencyKey()).isEqualTo("key-2");
        verify(submitMessageUseCase, times(2)).submit(any(SubmitCommand.class));
    }

    @Test
    @DisplayName("Submits a batch with mix of accepted items and idempotent replays")
    void testSubmitBatchWithReplay() {
        SubmitCommand cmd1 = createCommand("key-1", "{\"orderId\": 1}");
        SubmitCommand cmd2 = createCommand("key-2", "{\"orderId\": 2}");

        SubmitResult res1 = new SubmitResult(MessageId.generate(), MessageStatus.QUEUED, cmd1.correlationId(), Instant.now(), false);
        SubmitResult res2 = new SubmitResult(MessageId.generate(), MessageStatus.QUEUED, cmd2.correlationId(), Instant.now(), true); // Replay

        when(submitMessageUseCase.submit(cmd1)).thenReturn(res1);
        when(submitMessageUseCase.submit(cmd2)).thenReturn(res2);

        BulkSubmitResult batchResult = bulkService.submitBatch(new BulkSubmitCommand(clientId, List.of(cmd1, cmd2)));

        assertThat(batchResult.totalCount()).isEqualTo(2);
        assertThat(batchResult.acceptedCount()).isEqualTo(1);
        assertThat(batchResult.replayedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Rejects batch when size exceeds MAX_BATCH_SIZE (50)")
    void testSubmitBatchExceedingLimit() {
        List<SubmitCommand> items = new ArrayList<>();
        for (int i = 0; i <= BulkSubmitMessageUseCase.MAX_BATCH_SIZE; i++) {
            items.add(createCommand("key-" + i, "{\"val\": " + i + "}"));
        }

        assertThatThrownBy(() -> bulkService.submitBatch(new BulkSubmitCommand(clientId, items)))
                .isInstanceOf(BatchSizeExceededException.class)
                .hasMessageContaining("exceeds maximum allowed limit");
        verifyNoInteractions(submitMessageUseCase);
    }

    @Test
    @DisplayName("Rejects empty or null batch with EmptyBatchException")
    void testSubmitBatchEmptyOrNull() {
        assertThatThrownBy(() -> bulkService.submitBatch(null))
                .isInstanceOf(EmptyBatchException.class);

        assertThatThrownBy(() -> bulkService.submitBatch(new BulkSubmitCommand(clientId, List.of())))
                .isInstanceOf(EmptyBatchException.class);

        assertThatThrownBy(() -> bulkService.submitBatch(new BulkSubmitCommand(clientId, null)))
                .isInstanceOf(EmptyBatchException.class);
    }

    @Test
    @DisplayName("Allows intra-batch duplicate idempotency key if payload matches (idempotent replay)")
    void testIntraBatchDuplicateSamePayload() {
        SubmitCommand cmd1 = createCommand("key-same", "{\"orderId\": 100}");
        SubmitCommand cmd2 = createCommand("key-same", "{\"orderId\": 100}");

        SubmitResult res1 = new SubmitResult(MessageId.generate(), MessageStatus.QUEUED, cmd1.correlationId(), Instant.now(), false);
        SubmitResult res2 = new SubmitResult(res1.messageId(), MessageStatus.QUEUED, cmd1.correlationId(), Instant.now(), true);

        when(submitMessageUseCase.submit(any(SubmitCommand.class))).thenReturn(res1, res2);

        BulkSubmitResult batchResult = bulkService.submitBatch(new BulkSubmitCommand(clientId, List.of(cmd1, cmd2)));

        assertThat(batchResult.totalCount()).isEqualTo(2);
        assertThat(batchResult.acceptedCount()).isEqualTo(1);
        assertThat(batchResult.replayedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Rejects batch when intra-batch duplicate key has conflicting payload")
    void testIntraBatchDuplicateConflictingPayload() {
        SubmitCommand cmd1 = createCommand("key-conflict", "{\"orderId\": 100}");
        SubmitCommand cmd2 = createCommand("key-conflict", "{\"orderId\": 999}"); // Altered payload

        assertThatThrownBy(() -> bulkService.submitBatch(new BulkSubmitCommand(clientId, List.of(cmd1, cmd2))))
                .isInstanceOf(IdempotencyConflictException.class);

        verifyNoInteractions(submitMessageUseCase);
    }

    private SubmitCommand createCommand(String key, String payload) {
        return new SubmitCommand(
                clientId,
                messageType,
                "service-a",
                destinationKey,
                CorrelationId.generate(),
                "ext-ref",
                payload,
                new IdempotencyKey(key)
        );
    }
}
