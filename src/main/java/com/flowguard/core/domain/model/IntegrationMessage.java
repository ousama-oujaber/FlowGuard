package com.flowguard.core.domain.model;

import com.flowguard.core.domain.exception.DomainException;
import com.flowguard.core.domain.exception.InvalidPayloadException;
import com.flowguard.core.domain.exception.InvalidStateTransitionException;
import com.flowguard.core.domain.policy.MessageLifecyclePolicy;
import com.flowguard.core.domain.policy.RetryDecisionPolicy;
import com.flowguard.core.domain.value.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Aggregate Root guarding the lifecycle and processing invariants of an Integration Message.
 */
public class IntegrationMessage {

    private final MessageId id;
    private final ClientId clientId;
    private final MessageType messageType;
    private final String sourceSystem;
    private final DestinationKey destinationKey;
    private final CorrelationId correlationId;
    private final String externalReference;
    private final String payload;
    private final int payloadSizeBytes;
    private final PayloadFingerprint payloadFingerprint;

    private MessageStatus status;
    private int attemptCount;
    private final int maxAttempts;
    private Instant nextAttemptAt;

    private FailureCode lastErrorCode;
    private SafeErrorSummary lastErrorSummary;

    private String leaseOwner;
    private LeaseToken leaseToken;
    private Instant leaseUntil;

    private final Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
    private long version;

    public IntegrationMessage(
            MessageId id,
            ClientId clientId,
            MessageType messageType,
            String sourceSystem,
            DestinationKey destinationKey,
            CorrelationId correlationId,
            String externalReference,
            String payload,
            int payloadSizeBytes,
            PayloadFingerprint payloadFingerprint,
            MessageStatus status,
            int attemptCount,
            int maxAttempts,
            Instant nextAttemptAt,
            FailureCode lastErrorCode,
            SafeErrorSummary lastErrorSummary,
            String leaseOwner,
            LeaseToken leaseToken,
            Instant leaseUntil,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            long version
    ) {
        if (id == null) throw new DomainException("MessageId cannot be null");
        if (clientId == null) throw new DomainException("ClientId cannot be null");
        if (messageType == null) throw new DomainException("MessageType cannot be null");
        if (destinationKey == null) throw new DomainException("DestinationKey cannot be null");
        if (correlationId == null) throw new DomainException("CorrelationId cannot be null");
        if (payloadFingerprint == null) throw new DomainException("PayloadFingerprint cannot be null");
        if (status == null) throw new DomainException("MessageStatus cannot be null");
        if (attemptCount < 0) throw new DomainException("attemptCount cannot be negative");
        if (maxAttempts < 1) throw new DomainException("maxAttempts must be >= 1");
        if (createdAt == null) throw new DomainException("createdAt cannot be null");

        this.id = id;
        this.clientId = clientId;
        this.messageType = messageType;
        this.sourceSystem = sourceSystem;
        this.destinationKey = destinationKey;
        this.correlationId = correlationId;
        this.externalReference = externalReference;
        this.payload = payload;
        this.payloadSizeBytes = payloadSizeBytes;
        this.payloadFingerprint = payloadFingerprint;
        this.status = status;
        this.attemptCount = attemptCount;
        this.maxAttempts = maxAttempts;
        this.nextAttemptAt = nextAttemptAt;
        this.lastErrorCode = lastErrorCode;
        this.lastErrorSummary = lastErrorSummary;
        this.leaseOwner = leaseOwner;
        this.leaseToken = leaseToken;
        this.leaseUntil = leaseUntil;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt != null ? updatedAt : createdAt;
        this.completedAt = completedAt;
        this.version = version;
    }

    /**
     * Factory method for creating and accepting a new Integration Message.
     */
    public static IntegrationMessage accept(
            ClientId clientId,
            MessageType messageType,
            String sourceSystem,
            DestinationKey destinationKey,
            CorrelationId correlationId,
            String externalReference,
            String payload,
            int maxPayloadBytes,
            int maxAttempts
    ) {
        if (payload == null || payload.isBlank()) {
            throw new InvalidPayloadException("Payload cannot be null or empty");
        }
        byte[] bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > maxPayloadBytes) {
            throw new InvalidPayloadException("Payload size (" + bytes.length + " bytes) exceeds maximum allowed limit (" + maxPayloadBytes + " bytes)");
        }

        PayloadFingerprint fingerprint = PayloadFingerprint.compute(clientId, messageType, destinationKey, payload);
        Instant now = Instant.now();

        return new IntegrationMessage(
                MessageId.generate(),
                clientId,
                messageType,
                sourceSystem != null ? sourceSystem.trim() : "UNKNOWN",
                destinationKey,
                correlationId != null ? correlationId : CorrelationId.generate(),
                externalReference,
                payload,
                bytes.length,
                fingerprint,
                MessageStatus.QUEUED,
                0,
                maxAttempts > 0 ? maxAttempts : 5,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now,
                null,
                0L
        );
    }

    /**
     * Worker claims message for processing with a bounded lease.
     */
    public void claim(String workerId, LeaseToken token, Duration leaseDuration) {
        if (workerId == null || workerId.isBlank()) {
            throw new DomainException("workerId cannot be blank");
        }
        if (token == null) {
            throw new DomainException("LeaseToken cannot be null");
        }
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new DomainException("leaseDuration must be positive");
        }

        MessageLifecyclePolicy.validateTransition(this.status, MessageStatus.PROCESSING);

        this.status = MessageStatus.PROCESSING;
        this.attemptCount++;
        this.leaseOwner = workerId.trim();
        this.leaseToken = token;
        this.leaseUntil = Instant.now().plus(leaseDuration);
        this.nextAttemptAt = null;
        this.updatedAt = Instant.now();
    }

    /**
     * Completes processing successfully under lease fencing.
     */
    public void completeSuccess(LeaseToken activeToken) {
        validateLeaseOwnership(activeToken);
        MessageLifecyclePolicy.validateTransition(this.status, MessageStatus.SUCCEEDED);

        this.status = MessageStatus.SUCCEEDED;
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
        clearLease();
    }

    /**
     * Records a failure attempt and applies retry/dead-letter policy.
     */
    public RetryDecisionPolicy.Decision recordFailure(
            LeaseToken activeToken,
            ErrorCategory errorCategory,
            FailureCode errorCode,
            SafeErrorSummary safeSummary,
            RetryPolicy retryPolicy,
            Duration explicitRetryAfter
    ) {
        validateLeaseOwnership(activeToken);

        RetryDecisionPolicy.Decision decision = RetryDecisionPolicy.evaluate(
                this.attemptCount,
                this.maxAttempts,
                errorCategory,
                retryPolicy,
                explicitRetryAfter
        );

        MessageLifecyclePolicy.validateTransition(this.status, decision.nextStatus());

        this.status = decision.nextStatus();
        this.lastErrorCode = errorCode;
        this.lastErrorSummary = safeSummary;
        this.nextAttemptAt = decision.nextAttemptAt();
        this.updatedAt = Instant.now();

        if (this.status == MessageStatus.DEAD_LETTERED) {
            this.completedAt = this.updatedAt;
        }

        clearLease();
        return decision;
    }

    /**
     * Privileged manual retry command requested by an authorized operator.
     */
    public void requestManualRetry(String actorId, String reason) {
        if (actorId == null || actorId.isBlank()) {
            throw new DomainException("actorId cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new DomainException("Audit reason cannot be blank for manual retry");
        }

        if (this.status != MessageStatus.DEAD_LETTERED && this.status != MessageStatus.RECONCILIATION_REQUIRED) {
            throw new InvalidStateTransitionException(
                    "Cannot manually retry message in status [" + this.status + "]. Only DEAD_LETTERED or RECONCILIATION_REQUIRED messages are eligible."
            );
        }

        MessageLifecyclePolicy.validateTransition(this.status, MessageStatus.QUEUED);

        this.status = MessageStatus.QUEUED;
        this.nextAttemptAt = null;
        this.completedAt = null;
        this.updatedAt = Instant.now();
        clearLease();
    }

    /**
     * Explicit message cancellation requested before a delivery side effect begins.
     * Allowed only from QUEUED or RETRY_SCHEDULED status.
     */
    public void cancel(String actorId, String reason) {
        if (actorId == null || actorId.isBlank()) {
            throw new DomainException("actorId cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new DomainException("Cancellation reason cannot be blank");
        }

        if (this.status == MessageStatus.PROCESSING) {
            throw new InvalidStateTransitionException(
                    "Cannot cancel message [" + this.id + "]: Message is currently PROCESSING with in-flight worker dispatch."
            );
        }

        MessageLifecyclePolicy.validateTransition(this.status, MessageStatus.CANCELLED);

        this.status = MessageStatus.CANCELLED;
        this.nextAttemptAt = null;
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
        clearLease();
    }

    private void validateLeaseOwnership(LeaseToken activeToken) {
        if (this.status != MessageStatus.PROCESSING) {
            throw new InvalidStateTransitionException("Cannot complete attempt: Message is not in PROCESSING state (current: " + this.status + ")");
        }
        if (this.leaseToken == null || !Objects.equals(this.leaseToken, activeToken)) {
            throw new DomainException("Lease token mismatch: Stale worker result rejected. Active lease: " + this.leaseToken + ", worker token: " + activeToken);
        }
    }

    private void clearLease() {
        this.leaseOwner = null;
        this.leaseToken = null;
        this.leaseUntil = null;
    }

    // Getters
    public MessageId getId() { return id; }
    public ClientId getClientId() { return clientId; }
    public MessageType getMessageType() { return messageType; }
    public String getSourceSystem() { return sourceSystem; }
    public DestinationKey getDestinationKey() { return destinationKey; }
    public CorrelationId getCorrelationId() { return correlationId; }
    public String getExternalReference() { return externalReference; }
    public String getPayload() { return payload; }
    public int getPayloadSizeBytes() { return payloadSizeBytes; }
    public PayloadFingerprint getPayloadFingerprint() { return payloadFingerprint; }
    public MessageStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public FailureCode getLastErrorCode() { return lastErrorCode; }
    public SafeErrorSummary getLastErrorSummary() { return lastErrorSummary; }
    public String getLeaseOwner() { return leaseOwner; }
    public LeaseToken getLeaseToken() { return leaseToken; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public long getVersion() { return version; }
}
