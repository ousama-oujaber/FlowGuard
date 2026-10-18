package com.flowguard.core.application;

import com.flowguard.core.application.port.in.CancelMessageUseCase;
import com.flowguard.core.application.port.out.AuditRepositoryPort;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.application.port.out.TelemetryPort;
import com.flowguard.core.application.service.CancelMessageService;
import com.flowguard.core.domain.exception.InvalidStateTransitionException;
import com.flowguard.core.domain.exception.MessageNotFoundException;
import com.flowguard.core.domain.model.AuditEvent;
import com.flowguard.core.domain.model.ErrorCategory;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.value.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CancelMessageServiceTest {

    @Mock
    private MessageRepositoryPort messageRepository;

    @Mock
    private AuditRepositoryPort auditRepository;

    @Mock
    private TelemetryPort telemetryPort;

    private CancelMessageService service;

    @BeforeEach
    void setUp() {
        service = new CancelMessageService(messageRepository, auditRepository, telemetryPort);
    }

    private IntegrationMessage createQueuedMessage(ClientId clientId) {
        return IntegrationMessage.accept(
                clientId,
                new MessageType("PAYMENT_V1"),
                "SRC_APP",
                new DestinationKey("LEDGER_MAIN"),
                new CorrelationId("corr-123"),
                "EXT-001",
                "{\"amount\": 100.0}",
                262144,
                3
        );
    }

    @Test
    @DisplayName("Cancelling a QUEUED message succeeds, sets CANCELLED, and logs audit event")
    void cancelsQueuedMessageSuccessfully() {
        ClientId client = new ClientId("client_acme");
        IntegrationMessage message = createQueuedMessage(client);
        UUID messageId = message.getId().value();

        when(messageRepository.findById(new MessageId(messageId))).thenReturn(Optional.of(message));

        CancelMessageUseCase.CancelResult result =
                service.cancelMessage(messageId, "operator-alice", "Order cancelled by customer", "client_acme");

        assertThat(result.status()).isEqualTo(MessageStatus.CANCELLED);
        assertThat(result.messageId()).isEqualTo(messageId);
        assertThat(result.auditEventId()).isNotNull();

        verify(messageRepository).save(message);
        verify(auditRepository).save(any(AuditEvent.class));
        verify(telemetryPort).recordMessageCancelled();
    }

    @Test
    @DisplayName("Cancelling a RETRY_SCHEDULED message succeeds")
    void cancelsRetryScheduledMessage() {
        ClientId client = new ClientId("client_acme");
        IntegrationMessage message = createQueuedMessage(client);
        LeaseToken token = LeaseToken.generate();
        message.claim("worker-01", token, Duration.ofMinutes(1));
        message.recordFailure(token, ErrorCategory.TRANSIENT_NETWORK, new FailureCode("FAIL"), new SafeErrorSummary("err"), RetryPolicy.defaultPolicy(), null);

        UUID messageId = message.getId().value();
        when(messageRepository.findById(new MessageId(messageId))).thenReturn(Optional.of(message));

        CancelMessageUseCase.CancelResult result =
                service.cancelMessage(messageId, "operator-alice", "Duplicate item", "client_acme");

        assertThat(result.status()).isEqualTo(MessageStatus.CANCELLED);
    }

    @Test
    @DisplayName("Cancelling an in-flight PROCESSING message is rejected")
    void rejectsCancellingProcessingMessage() {
        ClientId client = new ClientId("client_acme");
        IntegrationMessage message = createQueuedMessage(client);
        LeaseToken token = LeaseToken.generate();
        message.claim("worker-01", token, Duration.ofMinutes(1));

        UUID messageId = message.getId().value();
        when(messageRepository.findById(new MessageId(messageId))).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> service.cancelMessage(messageId, "operator-alice", "Abort", "client_acme"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("PROCESSING");
    }

    @Test
    @DisplayName("Tenant isolation: Attempting to cancel another client's message throws MessageNotFoundException (404)")
    void tenantIsolationDeniesCrossClientCancellation() {
        ClientId clientOwner = new ClientId("client_acme");
        IntegrationMessage message = createQueuedMessage(clientOwner);
        UUID messageId = message.getId().value();

        when(messageRepository.findById(new MessageId(messageId))).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> service.cancelMessage(messageId, "attacker-bob", "Cancel enemy message", "client_rival"))
                .isInstanceOf(MessageNotFoundException.class);

        verify(messageRepository, never()).save(any());
        verify(auditRepository, never()).save(any());
    }
}
