package com.flowguard.core.application.service;

import com.flowguard.core.application.port.in.CancelMessageUseCase;
import com.flowguard.core.application.port.out.AuditRepositoryPort;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.domain.exception.DomainException;
import com.flowguard.core.domain.exception.InvalidStateTransitionException;
import com.flowguard.core.domain.exception.MessageNotFoundException;
import com.flowguard.core.domain.model.AuditEvent;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.value.MessageId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class CancelMessageService implements CancelMessageUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelMessageService.class);

    private final MessageRepositoryPort messageRepository;
    private final AuditRepositoryPort auditRepository;
    private final com.flowguard.core.application.port.out.TelemetryPort telemetryPort;

    public CancelMessageService(
            MessageRepositoryPort messageRepository,
            AuditRepositoryPort auditRepository,
            com.flowguard.core.application.port.out.TelemetryPort telemetryPort
    ) {
        this.messageRepository = messageRepository;
        this.auditRepository = auditRepository;
        this.telemetryPort = telemetryPort;
    }

    @Override
    @Transactional
    public CancelResult cancelMessage(UUID messageId, String actorId, String reason, String clientScope) {
        if (messageId == null) {
            throw new DomainException("messageId cannot be null");
        }
        if (actorId == null || actorId.isBlank()) {
            throw new DomainException("actorId cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new DomainException("Cancellation reason cannot be blank");
        }

        IntegrationMessage message = messageRepository.findById(new MessageId(messageId))
                .orElseThrow(() -> new MessageNotFoundException("Message not found: " + messageId));

        // Object-level authorization / Tenant isolation: caller cannot cancel another client's message
        if (clientScope != null && !clientScope.isBlank() && !message.getClientId().value().equals(clientScope)) {
            log.warn("Access denied: Client [{}] attempted to cancel message [{}] belonging to [{}]",
                    clientScope, messageId, message.getClientId().value());
            throw new MessageNotFoundException("Message not found: " + messageId);
        }

        MessageStatus previousStatus = message.getStatus();
        if (previousStatus != MessageStatus.QUEUED && previousStatus != MessageStatus.RETRY_SCHEDULED) {
            throw new InvalidStateTransitionException(
                    "Cannot cancel message [" + messageId + "] in status [" + previousStatus + "]. Only QUEUED or RETRY_SCHEDULED messages can be cancelled before side-effects begin."
            );
        }

        // Apply domain lifecycle transition to CANCELLED
        message.cancel(actorId, reason);
        messageRepository.save(message);

        // Record immutable audit event
        AuditEvent auditEvent = AuditEvent.create(
                actorId,
                "MESSAGE_CANCELLED",
                message.getId(),
                reason,
                Map.of(
                        "previousStatus", previousStatus.name(),
                        "newStatus", message.getStatus().name()
                )
        );
        auditRepository.save(auditEvent);
        if (telemetryPort != null) {
            telemetryPort.recordMessageCancelled();
        }

        log.info("Message [{}] cancelled from [{}] by actor [{}] with audit [{}]",
                message.getId(), previousStatus, actorId, auditEvent.id());

        return new CancelResult(
                message.getId().value(),
                message.getStatus(),
                auditEvent.id(),
                "Message successfully cancelled before dispatch side-effects."
        );
    }
}
