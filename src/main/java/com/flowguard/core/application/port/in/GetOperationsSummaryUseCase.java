package com.flowguard.core.application.port.in;

import com.flowguard.core.domain.model.MessageStatus;

import java.time.Instant;
import java.util.Map;

public interface GetOperationsSummaryUseCase {

    record OperationsSummary(
            Map<MessageStatus, Long> countsByStatus,
            long totalMessages,
            Long oldestQueuedAgeSeconds,
            Instant generatedAt
    ) {}

    OperationsSummary getSummary();
}
