package com.flowguard.adapters.inbound.worker;

import com.flowguard.core.application.port.in.RecoverExpiredLeasesUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "flowguard.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class LeaseRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(LeaseRecoveryScheduler.class);

    private final RecoverExpiredLeasesUseCase recoverExpiredLeasesUseCase;
    private final int batchSize;

    public LeaseRecoveryScheduler(
            RecoverExpiredLeasesUseCase recoverExpiredLeasesUseCase,
            @Value("${flowguard.recovery.batch-size:50}") int batchSize
    ) {
        this.recoverExpiredLeasesUseCase = recoverExpiredLeasesUseCase;
        this.batchSize = batchSize > 0 ? batchSize : 50;
    }

    @Scheduled(fixedDelayString = "${flowguard.recovery.poll-interval-ms:30000}")
    public void runRecovery() {
        try {
            RecoverExpiredLeasesUseCase.RecoveryResult result =
                    recoverExpiredLeasesUseCase.recover(batchSize, Duration.ofMinutes(1));
            if (result.recoveredCount() > 0) {
                log.info("Lease recovery cycle: recovered=[{}], deadLettered=[{}], reconciliation=[{}]",
                        result.recoveredCount(), result.deadLetteredCount(), result.reconciliationCount());
            }
        } catch (Exception ex) {
            log.error("Error during lease recovery cycle: {}", ex.getMessage(), ex);
        }
    }
}
