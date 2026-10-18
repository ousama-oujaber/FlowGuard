package com.flowguard.adapters.inbound.worker;

import com.flowguard.core.application.port.in.PurgeExpiredRecordsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Scheduled inbound adapter running periodically to clean up expired idempotency records.
 */
@Component
@ConditionalOnProperty(name = "flowguard.idempotency.retention-cleaner-enabled", havingValue = "true", matchIfMissing = true)
public class IdempotencyRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRetentionScheduler.class);

    private final PurgeExpiredRecordsUseCase purgeExpiredRecordsUseCase;

    public IdempotencyRetentionScheduler(PurgeExpiredRecordsUseCase purgeExpiredRecordsUseCase) {
        this.purgeExpiredRecordsUseCase = purgeExpiredRecordsUseCase;
    }

    @Scheduled(initialDelay = 60000, fixedDelayString = "${flowguard.idempotency.cleanup-interval-ms:3600000}")
    public void runRetentionCleanup() {
        try {
            int deleted = purgeExpiredRecordsUseCase.purgeExpiredRecords(Instant.now());
            if (deleted > 0) {
                log.info("Idempotency retention job finished: [{}] expired record(s) removed.", deleted);
            }
        } catch (Exception ex) {
            log.error("Error executing idempotency retention cleanup: {}", ex.getMessage(), ex);
        }
    }
}
