package com.flowguard.core.application.service;

import com.flowguard.core.application.port.in.ManualRetryUseCase;
import com.flowguard.core.application.port.out.AuditRepositoryPort;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.domain.exception.DomainException;
import com.flowguard.core.domain.exception.InvalidStateTransitionException;
import com.flowguard.core.domain.exception.MessageNotFoundException;
import com.flowguard.core.domain.model.AuditEvent;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.model.MessageStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class ManualRetryService implements ManualRetryUseCase {

    private static final Logger log = LoggerFactory.getLogger(ManualRetryService.class);

    private final MessageRepositoryPort messageRepository;
    private final AuditRepositoryPort auditRepository;

    public ManualRetryService(
            MessageRepositoryPort messageRepository,
            AuditRepositoryPort auditRepository
    ) {
        this.messageRepository = messageRepository;
        this.auditRepository = auditRepository;
    }

    @Override
    @Transactional
    public RetryResult retry(RetryCommand command) {
        if (command == null) {
            throw new DomainException("RetryCommand cannot be null");
        }
        if (command.messageId() == null) {
            throw new DomainException("MessageId cannot be null");
        }
        if (command.actorId() == null || command.actorId().isBlank()) {
            throw new DomainException("actorId cannot be blank for privileged manual retry");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw new DomainException("Audit reason cannot be blank for privileged manual retry");
        }

        IntegrationMessage message = messageRepository.findById(command.messageId())
                .orElseThrow(() -> new MessageNotFoundException("Message not found: " + command.messageId().value()));

        MessageStatus previousStatus = message.getStatus();
        if (previousStatus != MessageStatus.DEAD_LETTERED && previousStatus != MessageStatus.RECONCILIATION_REQUIRED) {
            throw new InvalidStateTransitionException(
                    "Cannot manually retry message in status [" + previousStatus + "]. Only DEAD_LETTERED or RECONCILIATION_REQUIRED messages are eligible."
            );
        }

        // Domain transition validates lifecycle invariants and resets nextAttemptAt, completedAt, lease
        message.requestManualRetry(command.actorId(), command.reason());
        messageRepository.save(message);

        // Record immutable audit event
        AuditEvent auditEvent = AuditEvent.create(
                command.actorId(),
                "MESSAGE_RETRY_REQUESTED",
                message.getId(),
                command.reason(),
                Map.of(
                        "previousStatus", previousStatus.name(),
                        "newStatus", message.getStatus().name()
                )
        );
        auditRepository.save(auditEvent);

        log.info("Message [{}] manually re-queued from [{}] by actor [{}] with audit [{}]",
                message.getId(), previousStatus, command.actorId(), auditEvent.id());

        return new RetryResult(
                message.getId(),
                message.getStatus(),
                auditEvent.id(),
                "Manual retry requested successfully; message re-queued for processing."
        );
    }
}
