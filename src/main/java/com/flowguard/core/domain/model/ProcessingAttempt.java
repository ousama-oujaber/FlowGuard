package com.flowguard.core.domain.model;

import com.flowguard.core.domain.exception.DomainException;
import com.flowguard.core.domain.value.FailureCode;
import com.flowguard.core.domain.value.MessageId;
import com.flowguard.core.domain.value.SafeErrorSummary;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class ProcessingAttempt {
    private final UUID id;
    private final MessageId messageId;
    private final int attemptNumber;
    private AttemptStatus status;
    private final String workerId;
    private final Instant startedAt;
    private Instant completedAt;
    private Long durationMs;
    private FailureCode errorCode;
    private ErrorCategory errorCategory;
    private Boolean retryable;
    private Integer targetStatusCode;
    private String traceId;
    private SafeErrorSummary safeSummary;

    public ProcessingAttempt(
            UUID id,
            MessageId messageId,
            int attemptNumber,
            AttemptStatus status,
            String workerId,
            Instant startedAt,
            Instant completedAt,
            Long durationMs,
            FailureCode errorCode,
            ErrorCategory errorCategory,
            Boolean retryable,
            Integer targetStatusCode,
            String traceId,
            SafeErrorSummary safeSummary
    ) {
        if (id == null) throw new DomainException("Attempt ID cannot be null");
        if (messageId == null) throw new DomainException("MessageId cannot be null");
        if (attemptNumber < 1) throw new DomainException("AttemptNumber must be >= 1");
        if (status == null) throw new DomainException("AttemptStatus cannot be null");
        if (startedAt == null) throw new DomainException("StartedAt cannot be null");

        this.id = id;
        this.messageId = messageId;
        this.attemptNumber = attemptNumber;
        this.status = status;
        this.workerId = workerId;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.durationMs = durationMs;
        this.errorCode = errorCode;
        this.errorCategory = errorCategory;
        this.retryable = retryable;
        this.targetStatusCode = targetStatusCode;
        this.traceId = traceId;
        this.safeSummary = safeSummary;
    }

    public static ProcessingAttempt start(MessageId messageId, int attemptNumber, String workerId, String traceId) {
        return new ProcessingAttempt(
                UUID.randomUUID(),
                messageId,
                attemptNumber,
                AttemptStatus.RUNNING,
                workerId,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                traceId,
                null
        );
    }

    public void completeSuccess(Integer targetStatusCode) {
        this.status = AttemptStatus.SUCCEEDED;
        this.completedAt = Instant.now();
        this.durationMs = Duration.between(this.startedAt, this.completedAt).toMillis();
        this.targetStatusCode = targetStatusCode;
        this.retryable = false;
    }

    public void completeFailure(
            FailureCode errorCode,
            ErrorCategory errorCategory,
            boolean retryable,
            Integer targetStatusCode,
            SafeErrorSummary safeSummary
    ) {
        this.status = AttemptStatus.FAILED;
        this.completedAt = Instant.now();
        this.durationMs = Duration.between(this.startedAt, this.completedAt).toMillis();
        this.errorCode = errorCode;
        this.errorCategory = errorCategory;
        this.retryable = retryable;
        this.targetStatusCode = targetStatusCode;
        this.safeSummary = safeSummary;
    }

    public void completeUnknown(
            FailureCode errorCode,
            Integer targetStatusCode,
            SafeErrorSummary safeSummary
    ) {
        this.status = AttemptStatus.UNKNOWN;
        this.completedAt = Instant.now();
        this.durationMs = Duration.between(this.startedAt, this.completedAt).toMillis();
        this.errorCode = errorCode;
        this.errorCategory = ErrorCategory.REMOTE_TIMEOUT_UNKNOWN;
        this.retryable = false;
        this.targetStatusCode = targetStatusCode;
        this.safeSummary = safeSummary;
    }

    // Getters
    public UUID getId() { return id; }
    public MessageId getMessageId() { return messageId; }
    public int getAttemptNumber() { return attemptNumber; }
    public AttemptStatus getStatus() { return status; }
    public String getWorkerId() { return workerId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Long getDurationMs() { return durationMs; }
    public FailureCode getErrorCode() { return errorCode; }
    public ErrorCategory getErrorCategory() { return errorCategory; }
    public Boolean getRetryable() { return retryable; }
    public Integer getTargetStatusCode() { return targetStatusCode; }
    public String getTraceId() { return traceId; }
    public SafeErrorSummary getSafeSummary() { return safeSummary; }
}
