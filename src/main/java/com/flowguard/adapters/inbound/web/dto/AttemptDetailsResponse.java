package com.flowguard.adapters.inbound.web.dto;

import com.flowguard.core.domain.model.ProcessingAttempt;
import java.time.Instant;
import java.util.UUID;

public record AttemptDetailsResponse(
        UUID id,
        UUID messageId,
        int attemptNumber,
        String status,
        String workerId,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        String errorCode,
        String errorCategory,
        Boolean retryable,
        Integer targetStatusCode,
        String traceId,
        String safeSummary
) {
    public static AttemptDetailsResponse fromDomain(ProcessingAttempt attempt) {
        return new AttemptDetailsResponse(
                attempt.getId(),
                attempt.getMessageId().value(),
                attempt.getAttemptNumber(),
                attempt.getStatus().name(),
                attempt.getWorkerId(),
                attempt.getStartedAt(),
                attempt.getCompletedAt(),
                attempt.getDurationMs(),
                attempt.getErrorCode() != null ? attempt.getErrorCode().value() : null,
                attempt.getErrorCategory() != null ? attempt.getErrorCategory().name() : null,
                attempt.getRetryable(),
                attempt.getTargetStatusCode(),
                attempt.getTraceId(),
                attempt.getSafeSummary() != null ? attempt.getSafeSummary().value() : null
        );
    }
}
