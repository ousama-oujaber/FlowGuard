package com.flowguard.core.application.port.in;

import java.time.Instant;

/**
 * Input port for purging expired idempotency mappings and temporary retention entries.
 */
public interface PurgeExpiredRecordsUseCase {
    int purgeExpiredRecords(Instant cutoff);
}
