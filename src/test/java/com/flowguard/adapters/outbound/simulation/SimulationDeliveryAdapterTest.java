package com.flowguard.adapters.outbound.simulation;

import com.flowguard.core.application.port.out.DeliveryPort.DeliveryResult;
import com.flowguard.core.domain.model.ErrorCategory;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.value.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationDeliveryAdapterTest {

    private final SimulationDeliveryAdapter adapter = new SimulationDeliveryAdapter("test");

    @Test
    @DisplayName("Default destination produces clean 200 OK success")
    void defaultDestinationSucceeds() {
        IntegrationMessage msg = createMessage("LEDGER_MAIN", "{}");
        DeliveryResult result = adapter.deliver(msg);

        assertThat(result.success()).isTrue();
        assertThat(result.targetStatusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("SIM_TIMEOUT destination produces ambiguous timeout for reconciliation")
    void timeoutProducesAmbiguousResult() {
        IntegrationMessage msg = createMessage("SIM_TIMEOUT_LEDGER", "{}");
        DeliveryResult result = adapter.deliver(msg);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ErrorCategory.REMOTE_TIMEOUT_UNKNOWN);
        assertThat(result.errorCode().value()).isEqualTo("SIMULATED_READ_TIMEOUT");
    }

    @Test
    @DisplayName("SIM_PERMANENT produces permanent business failure (HTTP 422)")
    void permanentFailureProducesDeadLetter() {
        IntegrationMessage msg = createMessage("SIM_PERMANENT_ERR", "{}");
        DeliveryResult result = adapter.deliver(msg);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ErrorCategory.PERMANENT_BUSINESS);
        assertThat(result.targetStatusCode()).isEqualTo(422);
    }

    @Test
    @DisplayName("SIM_TRANSIENT fails transiently on attempts 1 & 2, then succeeds on attempt 3")
    void transientFailsThenSucceeds() {
        IntegrationMessage msg = createMessage("SIM_TRANSIENT_GATEWAY", "{}");

        // Attempt 1 -> 504 failure
        msg.claim("worker-01", LeaseToken.generate(), java.time.Duration.ofMinutes(1));
        DeliveryResult r1 = adapter.deliver(msg);
        assertThat(r1.success()).isFalse();
        assertThat(r1.errorCategory()).isEqualTo(ErrorCategory.TRANSIENT_NETWORK);
        msg.recordFailure(msg.getLeaseToken(), r1.errorCategory(), r1.errorCode(), r1.safeSummary(), RetryPolicy.defaultPolicy(), null);

        // Attempt 2 -> 504 failure
        msg.claim("worker-01", LeaseToken.generate(), java.time.Duration.ofMinutes(1));
        DeliveryResult r2 = adapter.deliver(msg);
        assertThat(r2.success()).isFalse();
        msg.recordFailure(msg.getLeaseToken(), r2.errorCategory(), r2.errorCode(), r2.safeSummary(), RetryPolicy.defaultPolicy(), null);

        // Attempt 3 -> 200 Success
        msg.claim("worker-01", LeaseToken.generate(), java.time.Duration.ofMinutes(1));
        DeliveryResult r3 = adapter.deliver(msg);
        assertThat(r3.success()).isTrue();
        assertThat(r3.targetStatusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("SIM_RATE_LIMIT produces HTTP 429 with Retry-After duration")
    void rateLimitProducesRetryAfter() {
        IntegrationMessage msg = createMessage("SIM_RATE_LIMIT", "{}");
        DeliveryResult result = adapter.deliver(msg);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ErrorCategory.REMOTE_RATE_LIMIT);
        assertThat(result.targetStatusCode()).isEqualTo(429);
        assertThat(result.retryAfter()).isNotNull();
    }

    private IntegrationMessage createMessage(String destination, String payload) {
        return IntegrationMessage.accept(
                new ClientId("client_test"),
                new MessageType("EVENT_V1"),
                "SRC",
                new DestinationKey(destination),
                new CorrelationId("corr-test"),
                "EXT",
                payload,
                262144,
                5
        );
    }
}
