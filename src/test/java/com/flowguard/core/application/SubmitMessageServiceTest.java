package com.flowguard.core.application;

import com.flowguard.core.application.port.in.SubmitMessageUseCase.SubmitCommand;
import com.flowguard.core.application.port.in.SubmitMessageUseCase.SubmitResult;
import com.flowguard.core.application.port.out.IdempotencyRepositoryPort;
import com.flowguard.core.application.port.out.IdempotencyRepositoryPort.IdempotencyEntry;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.application.service.SubmitMessageService;
import com.flowguard.core.domain.exception.IdempotencyConflictException;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.value.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubmitMessageServiceTest {

    @Mock
    private MessageRepositoryPort messageRepository;

    @Mock
    private IdempotencyRepositoryPort idempotencyRepository;

    private SubmitMessageService service;

    private final ClientId clientId = new ClientId("client_acme");
    private final MessageType messageType = new MessageType("PAYROLL_V1");
    private final DestinationKey destinationKey = new DestinationKey("LEDGER_MAIN");
    private final CorrelationId correlationId = new CorrelationId("corr-123");
    private final IdempotencyKey idempotencyKey = new IdempotencyKey("idemp-key-abc");
    private final String payload = "{\"amount\":1000.00}";

    @BeforeEach
    void setUp() {
        service = new SubmitMessageService(messageRepository, idempotencyRepository, 262144, 5, 7);
    }

    @Test
    @DisplayName("First-time submission atomically saves message and idempotency record and returns 202 Accepted result")
    void firstTimeSubmission() {
        when(idempotencyRepository.find(eq(clientId), anyString())).thenReturn(Optional.empty());
        when(idempotencyRepository.save(any(IdempotencyEntry.class))).thenReturn(true);

        SubmitCommand command = new SubmitCommand(
                clientId, messageType, "HR_PORTAL", destinationKey, correlationId, "EXT-01", payload, idempotencyKey
        );

        SubmitResult result = service.submit(command);

        assertThat(result.messageId()).isNotNull();
        assertThat(result.status()).isEqualTo(MessageStatus.QUEUED);
        assertThat(result.idempotentReplay()).isFalse();

        verify(messageRepository, times(1)).save(any(IntegrationMessage.class));
        verify(idempotencyRepository, times(1)).save(any(IdempotencyEntry.class));
    }

    @Test
    @DisplayName("Concurrent insertion race conflict loads winning record and returns original message")
    void concurrentInsertionRaceWinningReplay() {
        // Insertion fails because other thread won the race
        when(idempotencyRepository.save(any(IdempotencyEntry.class))).thenReturn(false);

        PayloadFingerprint fp = PayloadFingerprint.compute(clientId, messageType, destinationKey, payload);
        MessageId winningId = MessageId.generate();
        Instant winningTime = Instant.now().minusSeconds(1);

        IdempotencyEntry winningEntry = new IdempotencyEntry(
                clientId, idempotencyKey.computeDigest(), fp, winningId, winningTime, winningTime.plusSeconds(86400)
        );
        IntegrationMessage winningMessage = IntegrationMessage.accept(
                clientId, messageType, "HR_PORTAL", destinationKey, correlationId, "EXT-01", payload, 262144, 5
        );

        // When reloading winning entry after save conflict
        when(idempotencyRepository.find(eq(clientId), anyString()))
                .thenReturn(Optional.empty()) // first check
                .thenReturn(Optional.of(winningEntry)); // reload after save conflict

        when(messageRepository.findById(winningId)).thenReturn(Optional.of(winningMessage));

        SubmitCommand command = new SubmitCommand(
                clientId, messageType, "HR_PORTAL", destinationKey, correlationId, "EXT-01", payload, idempotencyKey
        );

        SubmitResult result = service.submit(command);

        assertThat(result.messageId()).isEqualTo(winningMessage.getId());
        assertThat(result.idempotentReplay()).isTrue();
        verify(messageRepository, times(1)).delete(any(MessageId.class));
    }

    @Test
    @DisplayName("Concurrent insertion race with altered payload rejects with IdempotencyConflictException")
    void concurrentInsertionRaceAlteredPayloadConflict() {
        when(idempotencyRepository.save(any(IdempotencyEntry.class))).thenReturn(false);

        PayloadFingerprint winningFp = PayloadFingerprint.compute(clientId, messageType, destinationKey, "{\"other\":123}");
        MessageId winningId = MessageId.generate();
        Instant winningTime = Instant.now().minusSeconds(1);

        IdempotencyEntry winningEntry = new IdempotencyEntry(
                clientId, idempotencyKey.computeDigest(), winningFp, winningId, winningTime, winningTime.plusSeconds(86400)
        );

        when(idempotencyRepository.find(eq(clientId), anyString()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winningEntry));

        SubmitCommand command = new SubmitCommand(
                clientId, messageType, "HR_PORTAL", destinationKey, correlationId, "EXT-01", payload, idempotencyKey
        );

        assertThatThrownBy(() -> service.submit(command))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("Idempotency key reuse conflict");

        verify(messageRepository, times(1)).delete(any(MessageId.class));
    }

    @Test
    @DisplayName("Replaying the same key with identical payload returns existing message without duplicate saves")
    void identicalReplay() {
        PayloadFingerprint fp = PayloadFingerprint.compute(clientId, messageType, destinationKey, payload);
        MessageId existingId = MessageId.generate();
        Instant createdAt = Instant.now().minusSeconds(60);

        IdempotencyEntry existingEntry = new IdempotencyEntry(
                clientId, idempotencyKey.computeDigest(), fp, existingId, createdAt, createdAt.plusSeconds(86400)
        );

        IntegrationMessage existingMessage = IntegrationMessage.accept(
                clientId, messageType, "HR_PORTAL", destinationKey, correlationId, "EXT-01", payload, 262144, 5
        );

        when(idempotencyRepository.find(eq(clientId), anyString())).thenReturn(Optional.of(existingEntry));
        when(messageRepository.findById(existingId)).thenReturn(Optional.of(existingMessage));

        SubmitCommand command = new SubmitCommand(
                clientId, messageType, "HR_PORTAL", destinationKey, correlationId, "EXT-01", payload, idempotencyKey
        );

        SubmitResult result = service.submit(command);

        assertThat(result.messageId()).isEqualTo(existingMessage.getId());
        assertThat(result.idempotentReplay()).isTrue();

        verify(messageRepository, never()).save(any(IntegrationMessage.class));
    }

    @Test
    @DisplayName("Reusing idempotency key with altered payload throws IdempotencyConflictException")
    void alteredPayloadKeyReuse() {
        PayloadFingerprint originalFp = PayloadFingerprint.compute(clientId, messageType, destinationKey, "{\"amount\":500.00}");
        MessageId existingId = MessageId.generate();
        Instant createdAt = Instant.now();

        IdempotencyEntry existingEntry = new IdempotencyEntry(
                clientId, idempotencyKey.computeDigest(), originalFp, existingId, createdAt, createdAt.plusSeconds(86400)
        );

        when(idempotencyRepository.find(eq(clientId), anyString())).thenReturn(Optional.of(existingEntry));

        // Submit altered payload
        SubmitCommand command = new SubmitCommand(
                clientId, messageType, "HR_PORTAL", destinationKey, correlationId, "EXT-01", "{\"amount\":9999.00}", idempotencyKey
        );

        assertThatThrownBy(() -> service.submit(command))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("Idempotency key reuse conflict");

        verify(messageRepository, never()).save(any(IntegrationMessage.class));
    }
}
