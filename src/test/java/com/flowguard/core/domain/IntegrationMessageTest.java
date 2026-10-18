package com.flowguard.core.domain;

import com.flowguard.core.domain.exception.DomainException;
import com.flowguard.core.domain.exception.InvalidPayloadException;
import com.flowguard.core.domain.exception.InvalidStateTransitionException;
import com.flowguard.core.domain.model.ErrorCategory;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.value.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationMessageTest {

    private final ClientId clientId = new ClientId("client_test");
    private final MessageType messageType = new MessageType("ORDER_EVENT_V1");
    private final DestinationKey destinationKey = new DestinationKey("WAREHOUSE_API");
    private final CorrelationId correlationId = new CorrelationId("corr-123");
    private final String payload = "{\"orderId\":\"ORD-99\"}";

    @Test
    @DisplayName("Accepting a valid payload creates an initial QUEUED message with 0 attempts")
    void acceptValidMessage() {
        IntegrationMessage msg = IntegrationMessage.accept(
                clientId,
                messageType,
                "ORDER_SERVICE",
                destinationKey,
                correlationId,
                "EXT-ORD-99",
                payload,
                262144,
                5
        );

        assertThat(msg.getId()).isNotNull();
        assertThat(msg.getStatus()).isEqualTo(MessageStatus.QUEUED);
        assertThat(msg.getAttemptCount()).isZero();
        assertThat(msg.getMaxAttempts()).isEqualTo(5);
        assertThat(msg.getPayloadSizeBytes()).isGreaterThan(0);
        assertThat(msg.getPayloadFingerprint()).isNotNull();
    }

    @Test
    @DisplayName("Empty or null payload is rejected at acceptance")
    void rejectEmptyPayload() {
        assertThatThrownBy(() -> IntegrationMessage.accept(
                clientId, messageType, "ORDER_SERVICE", destinationKey, correlationId, "REF", "", 262144, 5
        )).isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    @DisplayName("Oversized payload is rejected at acceptance")
    void rejectOversizedPayload() {
        String largePayload = "a".repeat(100);
        assertThatThrownBy(() -> IntegrationMessage.accept(
                clientId, messageType, "ORDER_SERVICE", destinationKey, correlationId, "REF", largePayload, 50, 5
        )).isInstanceOf(InvalidPayloadException.class)
                .hasMessageContaining("exceeds maximum allowed limit");
    }

    @Test
    @DisplayName("Worker claiming transitions message to PROCESSING, increments attempt, and sets lease")
    void workerClaiming() {
        IntegrationMessage msg = createDefaultQueuedMessage();
        LeaseToken token = LeaseToken.generate();

        msg.claim("worker-01", token, Duration.ofMinutes(1));

        assertThat(msg.getStatus()).isEqualTo(MessageStatus.PROCESSING);
        assertThat(msg.getAttemptCount()).isEqualTo(1);
        assertThat(msg.getLeaseOwner()).isEqualTo("worker-01");
        assertThat(msg.getLeaseToken()).isEqualTo(token);
        assertThat(msg.getLeaseUntil()).isNotNull();
    }

    @Test
    @DisplayName("Completing with matching lease transitions to SUCCEEDED and clears lease")
    void completeSuccess() {
        IntegrationMessage msg = createDefaultQueuedMessage();
        LeaseToken token = LeaseToken.generate();
        msg.claim("worker-01", token, Duration.ofMinutes(1));

        msg.completeSuccess(token);

        assertThat(msg.getStatus()).isEqualTo(MessageStatus.SUCCEEDED);
        assertThat(msg.getCompletedAt()).isNotNull();
        assertThat(msg.getLeaseToken()).isNull();
        assertThat(msg.getLeaseOwner()).isNull();
    }

    @Test
    @DisplayName("Stale worker completing with mismatched lease token is rejected (lease fencing)")
    void staleWorkerFencing() {
        IntegrationMessage msg = createDefaultQueuedMessage();
        LeaseToken validToken = LeaseToken.generate();
        LeaseToken staleToken = LeaseToken.generate();

        msg.claim("worker-01", validToken, Duration.ofMinutes(1));

        assertThatThrownBy(() -> msg.completeSuccess(staleToken))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Lease token mismatch: Stale worker result rejected");
    }

    @Test
    @DisplayName("Recording transient failure transitions to RETRY_SCHEDULED")
    void recordTransientFailure() {
        IntegrationMessage msg = createDefaultQueuedMessage();
        LeaseToken token = LeaseToken.generate();
        msg.claim("worker-01", token, Duration.ofMinutes(1));

        msg.recordFailure(
                token,
                ErrorCategory.TRANSIENT_NETWORK,
                new FailureCode("CONN_RESET"),
                new SafeErrorSummary("Connection reset by remote peer"),
                RetryPolicy.defaultPolicy(),
                null
        );

        assertThat(msg.getStatus()).isEqualTo(MessageStatus.RETRY_SCHEDULED);
        assertThat(msg.getNextAttemptAt()).isNotNull();
        assertThat(msg.getLastErrorCode()).isEqualTo(new FailureCode("CONN_RESET"));
        assertThat(msg.getLeaseToken()).isNull();
    }

    @Test
    @DisplayName("Manual retry of a DEAD_LETTERED message re-queues it with audit record")
    void manualRetryEligibleMessage() {
        IntegrationMessage msg = createDefaultQueuedMessage();
        LeaseToken token = LeaseToken.generate();
        msg.claim("worker-01", token, Duration.ofMinutes(1));

        msg.recordFailure(
                token,
                ErrorCategory.PERMANENT_BUSINESS,
                new FailureCode("BAD_ACCOUNT"),
                new SafeErrorSummary("Account does not exist"),
                RetryPolicy.defaultPolicy(),
                null
        );
        assertThat(msg.getStatus()).isEqualTo(MessageStatus.DEAD_LETTERED);

        msg.requestManualRetry("operator-admin", "Account was provisioned and ready.");

        assertThat(msg.getStatus()).isEqualTo(MessageStatus.QUEUED);
        assertThat(msg.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("Manual retry of a SUCCEEDED message is forbidden")
    void manualRetryForbiddenOnSucceeded() {
        IntegrationMessage msg = createDefaultQueuedMessage();
        LeaseToken token = LeaseToken.generate();
        msg.claim("worker-01", token, Duration.ofMinutes(1));
        msg.completeSuccess(token);

        assertThatThrownBy(() -> msg.requestManualRetry("operator-admin", "Retry anyway"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Only DEAD_LETTERED or RECONCILIATION_REQUIRED messages are eligible");
    }

    @Test
    @DisplayName("Cancelling a QUEUED message transitions status to CANCELLED and sets completedAt")
    void cancelQueuedMessage() {
        IntegrationMessage msg = createDefaultQueuedMessage();
        msg.cancel("caller-alice", "Customer cancelled order");

        assertThat(msg.getStatus()).isEqualTo(MessageStatus.CANCELLED);
        assertThat(msg.getCompletedAt()).isNotNull();
        assertThat(msg.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("Cancelling a RETRY_SCHEDULED message transitions status to CANCELLED")
    void cancelRetryScheduledMessage() {
        IntegrationMessage msg = createDefaultQueuedMessage();
        LeaseToken token = LeaseToken.generate();
        msg.claim("worker-01", token, Duration.ofMinutes(1));
        msg.recordFailure(token, ErrorCategory.TRANSIENT_NETWORK, new FailureCode("FAIL"), new SafeErrorSummary("err"), RetryPolicy.defaultPolicy(), null);

        assertThat(msg.getStatus()).isEqualTo(MessageStatus.RETRY_SCHEDULED);

        msg.cancel("caller-alice", "No longer needed");
        assertThat(msg.getStatus()).isEqualTo(MessageStatus.CANCELLED);
    }

    @Test
    @DisplayName("Cancelling an in-flight PROCESSING message is forbidden")
    void cancelProcessingMessageForbidden() {
        IntegrationMessage msg = createDefaultQueuedMessage();
        LeaseToken token = LeaseToken.generate();
        msg.claim("worker-01", token, Duration.ofMinutes(1));

        assertThatThrownBy(() -> msg.cancel("caller-alice", "Stop it"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("PROCESSING");
    }

    private IntegrationMessage createDefaultQueuedMessage() {
        return IntegrationMessage.accept(
                clientId, messageType, "ORDER_SERVICE", destinationKey, correlationId, "REF", payload, 262144, 3
        );
    }
}
