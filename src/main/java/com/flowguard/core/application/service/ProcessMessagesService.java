package com.flowguard.core.application.service;

import com.flowguard.core.application.port.in.ProcessMessagesUseCase;
import com.flowguard.core.application.port.out.AttemptRepositoryPort;
import com.flowguard.core.application.port.out.DeliveryPort;
import com.flowguard.core.application.port.out.DeliveryPort.DeliveryResult;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.domain.model.ErrorCategory;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.model.ProcessingAttempt;
import com.flowguard.core.domain.value.LeaseToken;
import com.flowguard.core.domain.value.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class ProcessMessagesService implements ProcessMessagesUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessMessagesService.class);

    private final MessageRepositoryPort messageRepository;
    private final AttemptRepositoryPort attemptRepository;
    private final DeliveryPort deliveryPort;
    private final RetryPolicy retryPolicy;
    private final Duration leaseDuration;

    public ProcessMessagesService(
            MessageRepositoryPort messageRepository,
            AttemptRepositoryPort attemptRepository,
            DeliveryPort deliveryPort,
            @Value("${flowguard.retry.max-attempts:5}") int maxAttempts,
            @Value("${flowguard.retry.initial-delay-seconds:5}") long initialDelaySec,
            @Value("${flowguard.retry.max-delay-seconds:600}") long maxDelaySec,
            @Value("${flowguard.retry.multiplier:2.0}") double multiplier,
            @Value("${flowguard.retry.jitter-enabled:true}") boolean jitterEnabled,
            @Value("${flowguard.worker.lease-duration-seconds:60}") long leaseDurationSec
    ) {
        this.messageRepository = messageRepository;
        this.attemptRepository = attemptRepository;
        this.deliveryPort = deliveryPort;
        this.retryPolicy = new RetryPolicy(
                maxAttempts,
                Duration.ofSeconds(initialDelaySec),
                Duration.ofSeconds(maxDelaySec),
                multiplier,
                jitterEnabled
        );
        this.leaseDuration = Duration.ofSeconds(leaseDurationSec > 0 ? leaseDurationSec : 60);
    }

    @Override
    public int processDueMessages(int batchSize, String workerId) {
        LeaseToken leaseToken = LeaseToken.generate();
        Instant leaseUntil = Instant.now().plus(leaseDuration);

        // 1. Claim batch under short transaction
        List<IntegrationMessage> claimedMessages = claimBatch(batchSize, workerId, leaseToken, leaseUntil);
        if (claimedMessages.isEmpty()) {
            return 0;
        }

        log.info("Worker [{}] claimed batch of [{}] due message(s)", workerId, claimedMessages.size());

        for (IntegrationMessage message : claimedMessages) {
            processSingleMessage(message, workerId, message.getLeaseToken());
        }

        return claimedMessages.size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<IntegrationMessage> claimBatch(int batchSize, String workerId, LeaseToken token, Instant leaseUntil) {
        return messageRepository.claimDueMessages(batchSize, workerId, token, leaseUntil);
    }

    private void processSingleMessage(IntegrationMessage message, String workerId, LeaseToken leaseToken) {
        // Create initial RUNNING attempt record
        ProcessingAttempt attempt = ProcessingAttempt.start(
                message.getId(),
                message.getAttemptCount(),
                workerId,
                message.getCorrelationId().value()
        );
        attemptRepository.save(attempt);

        DeliveryResult result;
        try {
            // 2. Execute delivery port OUTSIDE database transaction
            result = deliveryPort.deliver(message);
        } catch (Exception ex) {
            log.error("Unexpected exception during delivery of message [{}]: {}", message.getId(), ex.getMessage(), ex);
            result = DeliveryResult.failure(
                    500,
                    ErrorCategory.INTERNAL_UNEXPECTED,
                    new com.flowguard.core.domain.value.FailureCode("UNEXPECTED_DELIVERY_EXCEPTION"),
                    new com.flowguard.core.domain.value.SafeErrorSummary("Unexpected delivery error: " + ex.getClass().getSimpleName()),
                    null
            );
        }

        // 3. Finalize attempt and update message under new short transaction with lease fencing
        finalizeOutcome(message, attempt, result, leaseToken);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeOutcome(
            IntegrationMessage message,
            ProcessingAttempt attempt,
            DeliveryResult result,
            LeaseToken leaseToken
    ) {
        if (result.success()) {
            attempt.completeSuccess(result.targetStatusCode());
            message.completeSuccess(leaseToken);
            log.info("Message [{}] processed successfully (SUCCEEDED).", message.getId());
        } else if (result.errorCategory() == ErrorCategory.REMOTE_TIMEOUT_UNKNOWN) {
            attempt.completeUnknown(result.errorCode(), result.targetStatusCode(), result.safeSummary());
            message.recordFailure(
                    leaseToken,
                    result.errorCategory(),
                    result.errorCode(),
                    result.safeSummary(),
                    retryPolicy,
                    null
            );
            log.warn("Message [{}] routed to RECONCILIATION_REQUIRED due to ambiguous target timeout.", message.getId());
        } else {
            attempt.completeFailure(
                    result.errorCode(),
                    result.errorCategory(),
                    result.errorCategory().isDefaultRetryable(),
                    result.targetStatusCode(),
                    result.safeSummary()
            );
            message.recordFailure(
                    leaseToken,
                    result.errorCategory(),
                    result.errorCode(),
                    result.safeSummary(),
                    retryPolicy,
                    result.retryAfter()
            );
            log.warn("Message [{}] delivery failed. Status transitioned to [{}].", message.getId(), message.getStatus());
        }

        attemptRepository.save(attempt);

        // Apply lease fence conditional update
        boolean updated = messageRepository.updateStatusWithLeaseFence(message, leaseToken);
        if (!updated) {
            log.error("STALE WORKER DETECTED: Message [{}] outcome rejected because active lease token [{}] was superseded.",
                    message.getId(), leaseToken);
        }
    }
}
