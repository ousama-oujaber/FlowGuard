package com.flowguard.core.application.service;

import com.flowguard.core.application.port.in.RecoverExpiredLeasesUseCase;
import com.flowguard.core.application.port.out.AttemptRepositoryPort;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.domain.model.AttemptStatus;
import com.flowguard.core.domain.model.ErrorCategory;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.model.ProcessingAttempt;
import com.flowguard.core.domain.value.FailureCode;
import com.flowguard.core.domain.value.LeaseToken;
import com.flowguard.core.domain.value.RetryPolicy;
import com.flowguard.core.domain.value.SafeErrorSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class RecoverExpiredLeasesService implements RecoverExpiredLeasesUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecoverExpiredLeasesService.class);

    private final MessageRepositoryPort messageRepository;
    private final AttemptRepositoryPort attemptRepository;
    private final RetryPolicy retryPolicy;

    public RecoverExpiredLeasesService(
            MessageRepositoryPort messageRepository,
            AttemptRepositoryPort attemptRepository,
            @Value("${flowguard.retry.max-attempts:5}") int maxAttempts
    ) {
        this.messageRepository = messageRepository;
        this.attemptRepository = attemptRepository;
        this.retryPolicy = new RetryPolicy(maxAttempts, Duration.ofSeconds(5), Duration.ofMinutes(10), 2.0, true);
    }

    @Override
    @Transactional
    public RecoveryResult recover(int batchSize, Duration maxLeaseAge) {
        int limit = batchSize > 0 ? batchSize : 50;
        Instant cutoff = Instant.now();

        List<IntegrationMessage> expiredMessages = messageRepository.findExpiredLeases(cutoff, limit);
        if (expiredMessages.isEmpty()) {
            return new RecoveryResult(0, 0, 0);
        }

        int recoveredCount = 0;
        int deadLetteredCount = 0;
        int reconciliationCount = 0;

        for (IntegrationMessage message : expiredMessages) {
            LeaseToken staleToken = message.getLeaseToken();
            if (staleToken == null) {
                continue;
            }

            // Finalize any still-running attempt
            List<ProcessingAttempt> attempts = attemptRepository.findByMessageId(message.getId());
            attempts.stream()
                    .filter(a -> a.getStatus() == AttemptStatus.RUNNING)
                    .forEach(runningAttempt -> {
                        runningAttempt.completeUnknown(
                                new FailureCode("LEASE_EXPIRED"),
                                504,
                                new SafeErrorSummary("Worker lease expired without report; crash or hang suspected")
                        );
                        attemptRepository.save(runningAttempt);
                    });

            // Transition message using domain failure policy
            FailureCode failureCode = new FailureCode("LEASE_EXPIRED");
            SafeErrorSummary errorSummary = new SafeErrorSummary("Worker lease expired at " + message.getLeaseUntil());

            ErrorCategory category = message.getAttemptCount() >= message.getMaxAttempts()
                    ? ErrorCategory.INTERNAL_UNEXPECTED
                    : ErrorCategory.REMOTE_TIMEOUT_UNKNOWN;

            message.recordFailure(
                    staleToken,
                    category,
                    failureCode,
                    errorSummary,
                    retryPolicy,
                    null
            );

            boolean updated = messageRepository.updateStatusWithLeaseFence(message, staleToken);
            if (updated) {
                recoveredCount++;
                if (message.getStatus() == MessageStatus.DEAD_LETTERED) {
                    deadLetteredCount++;
                    log.warn("Recovered expired lease for message [{}]: Exhausted attempts, transitioned to DEAD_LETTERED.", message.getId());
                } else if (message.getStatus() == MessageStatus.RECONCILIATION_REQUIRED) {
                    reconciliationCount++;
                    log.warn("Recovered expired lease for message [{}]: Ambiguous in-flight state, transitioned to RECONCILIATION_REQUIRED.", message.getId());
                }
            } else {
                log.info("Expired lease update fenced for message [{}]: Active lease token already changed.", message.getId());
            }
        }

        return new RecoveryResult(recoveredCount, deadLetteredCount, reconciliationCount);
    }
}
