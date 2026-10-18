package com.flowguard.core.application.service;

import com.flowguard.core.application.port.in.SubmitMessageUseCase;
import com.flowguard.core.application.port.out.IdempotencyRepositoryPort;
import com.flowguard.core.application.port.out.IdempotencyRepositoryPort.IdempotencyEntry;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.domain.exception.DomainException;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.policy.IdempotencyPolicy;
import com.flowguard.core.domain.value.PayloadFingerprint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class SubmitMessageService implements SubmitMessageUseCase {

    private final MessageRepositoryPort messageRepository;
    private final IdempotencyRepositoryPort idempotencyRepository;
    private final int maxPayloadBytes;
    private final int defaultMaxAttempts;
    private final Duration idempotencyRetention;

    public SubmitMessageService(
            MessageRepositoryPort messageRepository,
            IdempotencyRepositoryPort idempotencyRepository,
            @Value("${flowguard.max-payload-bytes:262144}") int maxPayloadBytes,
            @Value("${flowguard.retry.max-attempts:5}") int defaultMaxAttempts,
            @Value("${flowguard.idempotency.retention-days:7}") int retentionDays
    ) {
        this.messageRepository = messageRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.maxPayloadBytes = maxPayloadBytes;
        this.defaultMaxAttempts = defaultMaxAttempts;
        this.idempotencyRetention = Duration.ofDays(retentionDays > 0 ? retentionDays : 7);
    }

    @Override
    @Transactional
    public SubmitResult submit(SubmitCommand command) {
        if (command == null) {
            throw new DomainException("SubmitCommand cannot be null");
        }
        if (command.idempotencyKey() == null) {
            throw new DomainException("IdempotencyKey is required for message submission");
        }

        String keyDigest = command.idempotencyKey().computeDigest();
        Optional<IdempotencyEntry> existingEntry = idempotencyRepository.find(command.clientId(), keyDigest);

        PayloadFingerprint incomingFingerprint = PayloadFingerprint.compute(
                command.clientId(),
                command.messageType(),
                command.destinationKey(),
                command.payload()
        );

        if (existingEntry.isPresent()) {
            IdempotencyEntry entry = existingEntry.get();
            // Verify idempotency fingerprint match; throws IdempotencyConflictException if altered
            IdempotencyPolicy.verify(entry.requestFingerprint(), incomingFingerprint);

            IntegrationMessage existingMessage = messageRepository.findById(entry.messageId())
                    .orElseThrow(() -> new DomainException("Referenced idempotent message not found: " + entry.messageId()));

            return new SubmitResult(
                    existingMessage.getId(),
                    existingMessage.getStatus(),
                    existingMessage.getCorrelationId(),
                    entry.createdAt(),
                    true // idempotent replay
            );
        }

        // New acceptance
        IntegrationMessage message = IntegrationMessage.accept(
                command.clientId(),
                command.messageType(),
                command.sourceSystem(),
                command.destinationKey(),
                command.correlationId(),
                command.externalReference(),
                command.payload(),
                maxPayloadBytes,
                defaultMaxAttempts
        );

        messageRepository.save(message);

        Instant now = Instant.now();
        IdempotencyEntry newEntry = new IdempotencyEntry(
                command.clientId(),
                keyDigest,
                incomingFingerprint,
                message.getId(),
                now,
                now.plus(idempotencyRetention)
        );
        boolean saved = idempotencyRepository.save(newEntry);
        if (!saved) {
            // Concurrent submission conflict: Another thread won the insertion race on (clientId, keyDigest).
            // Clean up orphan message created in this concurrent attempt:
            messageRepository.delete(message.getId());

            // Load and compare the winning record
            IdempotencyEntry winningEntry = idempotencyRepository.find(command.clientId(), keyDigest)
                    .orElseThrow(() -> new DomainException("Idempotency conflict detected but winning record not found"));

            // Verifies fingerprint match; throws IdempotencyConflictException (409) if altered
            IdempotencyPolicy.verify(winningEntry.requestFingerprint(), incomingFingerprint);

            IntegrationMessage winningMessage = messageRepository.findById(winningEntry.messageId())
                    .orElseThrow(() -> new DomainException("Winning idempotent message not found: " + winningEntry.messageId()));

            return new SubmitResult(
                    winningMessage.getId(),
                    winningMessage.getStatus(),
                    winningMessage.getCorrelationId(),
                    winningEntry.createdAt(),
                    true // idempotent replay
            );
        }

        return new SubmitResult(
                message.getId(),
                message.getStatus(),
                message.getCorrelationId(),
                now,
                false
        );
    }
}
