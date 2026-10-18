package com.flowguard.adapters.inbound.web.dto;

import com.flowguard.core.application.port.in.GetOperationsSummaryUseCase.OperationsSummary;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

public record OperationsSummaryResponse(
        Map<String, Long> countsByStatus,
        long totalMessages,
        Long oldestQueuedAgeSeconds,
        Instant generatedAt
) {
    public static OperationsSummaryResponse fromDomain(OperationsSummary summary) {
        Map<String, Long> stringCounts = summary.countsByStatus().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));
        return new OperationsSummaryResponse(
                stringCounts,
                summary.totalMessages(),
                summary.oldestQueuedAgeSeconds(),
                summary.generatedAt()
        );
    }
}
