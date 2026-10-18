package com.flowguard.adapters.outbound.simulation;

import com.flowguard.core.application.port.out.DeliveryPort;
import com.flowguard.core.domain.exception.DomainException;
import com.flowguard.core.domain.model.ErrorCategory;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.value.FailureCode;
import com.flowguard.core.domain.value.SafeErrorSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Deterministic delivery simulator for testing message lifecycle and failure scenarios.
 * Forbidden in production environments.
 */
@Component
@Profile("!prod")
public class SimulationDeliveryAdapter implements DeliveryPort {

    private static final Logger log = LoggerFactory.getLogger(SimulationDeliveryAdapter.class);

    private final String activeProfile;

    public SimulationDeliveryAdapter(@Value("${spring.profiles.active:default}") String activeProfile) {
        this.activeProfile = activeProfile;
        if ("prod".equalsIgnoreCase(activeProfile) || "production".equalsIgnoreCase(activeProfile)) {
            throw new IllegalStateException("FATAL: SimulationDeliveryAdapter cannot be loaded in a production profile!");
        }
    }

    @Override
    public DeliveryResult deliver(IntegrationMessage message) {
        if (message == null) {
            throw new DomainException("Cannot deliver null IntegrationMessage");
        }

        String dest = message.getDestinationKey().value().toUpperCase();
        String payload = message.getPayload() != null ? message.getPayload() : "";

        log.info("Simulating delivery for message [{}] destination [{}] attempt [{}]",
                message.getId(), dest, message.getAttemptCount());

        // 1. Ambiguous Timeout -> RECONCILIATION_REQUIRED
        if (dest.contains("TIMEOUT") || payload.contains("\"simulate\":\"TIMEOUT\"")) {
            log.warn("Simulator trigger: Ambiguous socket read timeout for message [{}]", message.getId());
            return DeliveryResult.ambiguousTimeout(
                    new FailureCode("SIMULATED_READ_TIMEOUT"),
                    new SafeErrorSummary("Remote target read timed out after request dispatched; outcome is ambiguous.")
            );
        }

        // 2. Permanent Failure -> DEAD_LETTERED
        if (dest.contains("PERMANENT") || payload.contains("\"simulate\":\"PERMANENT\"")) {
            log.warn("Simulator trigger: Permanent business rejection for message [{}]", message.getId());
            return DeliveryResult.failure(
                    422,
                    ErrorCategory.PERMANENT_BUSINESS,
                    new FailureCode("SIMULATED_BUSINESS_REJECTION"),
                    new SafeErrorSummary("Target rejected message due to unprocessable business data."),
                    null
            );
        }

        // 3. Transient Failure -> Retries up to 2 times, then succeeds on attempt 3
        if (dest.contains("TRANSIENT") || payload.contains("\"simulate\":\"TRANSIENT\"")) {
            if (message.getAttemptCount() < 3) {
                log.warn("Simulator trigger: Transient gateway timeout for message [{}], attempt [{}]",
                        message.getId(), message.getAttemptCount());
                return DeliveryResult.failure(
                        504,
                        ErrorCategory.TRANSIENT_NETWORK,
                        new FailureCode("SIMULATED_GATEWAY_TIMEOUT"),
                        new SafeErrorSummary("Gateway connection timeout; peer temporarily unreachable."),
                        null
                );
            }
            log.info("Simulator trigger: Transient condition resolved for message [{}]; succeeding on attempt [{}]",
                    message.getId(), message.getAttemptCount());
            return DeliveryResult.success(200);
        }

        // 4. Rate Limit with Retry-After header
        if (dest.contains("RATE_LIMIT") || payload.contains("\"simulate\":\"RATE_LIMIT\"")) {
            log.warn("Simulator trigger: Rate limit (HTTP 429) for message [{}]", message.getId());
            return DeliveryResult.failure(
                    429,
                    ErrorCategory.REMOTE_RATE_LIMIT,
                    new FailureCode("SIMULATED_RATE_LIMIT"),
                    new SafeErrorSummary("Target throttled request with Retry-After header."),
                    Duration.ofSeconds(3)
            );
        }

        // Default: Clean Success
        log.info("Simulator default: Message [{}] successfully delivered.", message.getId());
        return DeliveryResult.success(200);
    }
}
