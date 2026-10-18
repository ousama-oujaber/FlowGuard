package com.flowguard.core.application.port.in;

import java.time.Duration;

public interface RecoverExpiredLeasesUseCase {

    record RecoveryResult(
            int recoveredCount,
            int deadLetteredCount,
            int reconciliationCount
    ) {}

    RecoveryResult recover(int batchSize, Duration maxLeaseAge);
}
