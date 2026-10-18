package com.flowguard.core.application;

import com.flowguard.core.application.port.in.ManualRetryUseCase;
import com.flowguard.core.application.port.out.AuditRepositoryPort;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.application.service.ManualRetryService;
import com.flowguard.core.domain.exception.DomainException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManualRetryServiceTest {

    @Mock
    private MessageRepositoryPort messageRepository;

    @Mock
    private AuditRepositoryPort auditRepository;

    private ManualRetryService service;

    @BeforeEach
    void setUp() {
        service = new ManualRetryService(messageRepository, auditRepository);
    }

    private IntegrationMessage createMessageWithStatus(MessageStatus status) {
        IntegrationMessage message = IntegrationMessage.accept(
                new ClientId("client_test"),
                new MessageType("ORDER_V1"),
                "PORTAL",
                new DestinationKey("ORDER_SERVICE"),
                new CorrelationId("corr-1"),
                "EXT-1",
                "{\"item\":\"apple\"}",
                262144,
                5
        );

        if (status == MessageStatus.QUEUED) {
            return message;
        }

        LeaseToken token = LeaseToken.generate();
        message.claim("worker-1", token, Duration.ofMinutes(1));

        if (status == MessageStatus.PROCESSING) {
            return message;
        }

        if (status == MessageStatus.SUCCEEDED) {
            message.completeSuccess(token);
            return message;
        }

        if (status == MessageStatus.DEAD_LETTERED) {
            message.recordFailure(
                    token,
                    ErrorCategory.PERMANENT_BUSINESS,
                    new FailureCode("ERR_422"),
                    new SafeErrorSummary("Unprocessable"),
                    RetryPolicy.defaultPolicy(),
                    null
            );
            return message;
        }

        if (status == MessageStatus.RECONCILIATION_REQUIRED) {
            message.recordFailure(
                    token,
                    ErrorCategory.REMOTE_TIMEOUT_UNKNOWN,
                    new FailureCode("TIMEOUT"),
                    new SafeErrorSummary("Socket timeout"),
                    RetryPolicy.defaultPolicy(),
                    null
            );
            return message;
        }

        return message;
    }

    @Test
    @DisplayName("Manual retry of DEAD_LETTERED message re-queues message and creates audit event")
    void retryDeadLetteredMessageSucceeds() {
        IntegrationMessage deadLettered = createMessageWithStatus(MessageStatus.DEAD_LETTERED);
        MessageId messageId = deadLettered.getId();

        when(messageRepository.findById(messageId)).thenReturn(Optional.of(deadLettered));

        ManualRetryUseCase.RetryCommand command = new ManualRetryUseCase.RetryCommand(
                messageId,
                "ops-user-42",
                "Target system configuration was corrected."
        );

        ManualRetryUseCase.RetryResult result = service.retry(command);

        assertThat(result.messageId()).isEqualTo(messageId);
        assertThat(result.status()).isEqualTo(MessageStatus.QUEUED);
        assertThat(result.auditEventId()).isNotNull();

        verify(messageRepository, times(1)).save(deadLettered);
        assertThat(deadLettered.getStatus()).isEqualTo(MessageStatus.QUEUED);

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRepository, times(1)).save(auditCaptor.capture());

        AuditEvent savedAudit = auditCaptor.getValue();
        assertThat(savedAudit.actorId()).isEqualTo("ops-user-42");
        assertThat(savedAudit.action()).isEqualTo("MESSAGE_RETRY_REQUESTED");
        assertThat(savedAudit.messageId()).isEqualTo(messageId);
        assertThat(savedAudit.reason()).isEqualTo("Target system configuration was corrected.");
        assertThat(savedAudit.metadata()).containsEntry("previousStatus", "DEAD_LETTERED");
        assertThat(savedAudit.metadata()).containsEntry("newStatus", "QUEUED");
    }

    @Test
    @DisplayName("Manual retry of RECONCILIATION_REQUIRED message re-queues message and creates audit event")
    void retryReconciliationRequiredMessageSucceeds() {
        IntegrationMessage reconciliationMessage = createMessageWithStatus(MessageStatus.RECONCILIATION_REQUIRED);
        MessageId messageId = reconciliationMessage.getId();

        when(messageRepository.findById(messageId)).thenReturn(Optional.of(reconciliationMessage));

        ManualRetryUseCase.RetryCommand command = new ManualRetryUseCase.RetryCommand(
                messageId,
                "lead-engineer",
                "Remote ledger confirmed the initial transaction was not applied."
        );

        ManualRetryUseCase.RetryResult result = service.retry(command);

        assertThat(result.status()).isEqualTo(MessageStatus.QUEUED);
        verify(messageRepository, times(1)).save(reconciliationMessage);
        verify(auditRepository, times(1)).save(any(AuditEvent.class));
    }

    @Test
    @DisplayName("Manual retry of SUCCEEDED message throws InvalidStateTransitionException")
    void retrySucceededMessageThrowsConflict() {
        IntegrationMessage succeededMessage = createMessageWithStatus(MessageStatus.SUCCEEDED);
        MessageId messageId = succeededMessage.getId();

        when(messageRepository.findById(messageId)).thenReturn(Optional.of(succeededMessage));

        ManualRetryUseCase.RetryCommand command = new ManualRetryUseCase.RetryCommand(
                messageId,
                "ops-user-42",
                "Retrying completed work."
        );

        assertThatThrownBy(() -> service.retry(command))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Cannot manually retry message in status [SUCCEEDED]");

        verify(messageRepository, never()).save(any());
        verify(auditRepository, never()).save(any());
    }

    @Test
    @DisplayName("Manual retry of QUEUED or PROCESSING message throws InvalidStateTransitionException")
    void retryQueuedMessageThrowsConflict() {
        IntegrationMessage queuedMessage = createMessageWithStatus(MessageStatus.QUEUED);
        MessageId messageId = queuedMessage.getId();

        when(messageRepository.findById(messageId)).thenReturn(Optional.of(queuedMessage));

        ManualRetryUseCase.RetryCommand command = new ManualRetryUseCase.RetryCommand(
                messageId,
                "ops-user-42",
                "Premature retry."
        );

        assertThatThrownBy(() -> service.retry(command))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Cannot manually retry message in status [QUEUED]");
    }

    @Test
    @DisplayName("Manual retry of non-existent message throws MessageNotFoundException")
    void retryNonExistentMessageThrowsNotFound() {
        MessageId messageId = MessageId.generate();
        when(messageRepository.findById(messageId)).thenReturn(Optional.empty());

        ManualRetryUseCase.RetryCommand command = new ManualRetryUseCase.RetryCommand(
                messageId,
                "ops-user-42",
                "Reason"
        );

        assertThatThrownBy(() -> service.retry(command))
                .isInstanceOf(MessageNotFoundException.class)
                .hasMessageContaining("Message not found");
    }

    @Test
    @DisplayName("Manual retry with blank actorId or blank reason throws DomainException")
    void retryBlankActorOrReasonThrowsDomainException() {
        MessageId messageId = MessageId.generate();

        assertThatThrownBy(() -> service.retry(new ManualRetryUseCase.RetryCommand(messageId, "", "valid reason")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("actorId cannot be blank");

        assertThatThrownBy(() -> service.retry(new ManualRetryUseCase.RetryCommand(messageId, "actor-1", "   ")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Audit reason cannot be blank");
    }
}
