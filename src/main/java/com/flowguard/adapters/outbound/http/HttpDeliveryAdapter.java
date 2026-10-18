package com.flowguard.adapters.outbound.http;

import com.flowguard.core.application.port.out.DeliveryPort;
import com.flowguard.core.domain.exception.DomainException;
import com.flowguard.core.domain.model.ErrorCategory;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.value.FailureCode;
import com.flowguard.core.domain.value.SafeErrorSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;

/**
 * Production-ready HTTP outbound delivery adapter implementing DeliveryPort.
 * Dispatches messages to authorized downstream enterprise endpoints via HttpClient.
 */
@Component
@Profile("prod")
public class HttpDeliveryAdapter implements DeliveryPort {

    private static final Logger log = LoggerFactory.getLogger(HttpDeliveryAdapter.class);

    private final DestinationRegistry destinationRegistry;
    private final HttpClient httpClient;

    public HttpDeliveryAdapter(DestinationRegistry destinationRegistry) {
        this(destinationRegistry, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    public HttpDeliveryAdapter(DestinationRegistry destinationRegistry, HttpClient httpClient) {
        this.destinationRegistry = destinationRegistry;
        this.httpClient = httpClient;
    }

    @Override
    public DeliveryResult deliver(IntegrationMessage message) {
        if (message == null) {
            throw new DomainException("Cannot deliver null IntegrationMessage");
        }

        String destKey = message.getDestinationKey().value();
        Optional<DestinationRegistry.DestinationConfig> configOpt = destinationRegistry.get(destKey);

        if (configOpt.isEmpty()) {
            log.warn("Delivery rejected: Destination [{}] is not configured in egress allowlist.", destKey);
            return DeliveryResult.failure(
                    400,
                    ErrorCategory.CONFIGURATION_FAULT,
                    new FailureCode("UNREGISTERED_DESTINATION"),
                    new SafeErrorSummary("Destination [" + destKey + "] is not registered in authorized egress allowlist."),
                    null
            );
        }

        DestinationRegistry.DestinationConfig config = configOpt.get();

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(config.targetUrl()))
                    .timeout(config.readTimeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-Correlation-ID", message.getCorrelationId().value())
                    .header("Idempotency-Key", message.getId().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            message.getPayload() != null ? message.getPayload() : "{}"
                    ));

            config.defaultHeaders().forEach(reqBuilder::header);
            HttpRequest request = reqBuilder.build();

            log.info("Dispatching outbound HTTP request for message [{}] to target [{}]", message.getId(), config.targetUrl());

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            log.info("Received HTTP response [{}] for message [{}]", statusCode, message.getId());

            // 2xx -> Success
            if (statusCode >= 200 && statusCode < 300) {
                return DeliveryResult.success(statusCode);
            }

            // 429 -> Rate Limited with Retry-After
            if (statusCode == 429) {
                Duration retryAfter = parseRetryAfter(response);
                return DeliveryResult.failure(
                        429,
                        ErrorCategory.REMOTE_RATE_LIMIT,
                        new FailureCode("HTTP_429_RATE_LIMITED"),
                        new SafeErrorSummary("Target throttled request with HTTP 429 Too Many Requests."),
                        retryAfter
                );
            }

            // 4xx -> Permanent Business Error (Client error)
            if (statusCode >= 400 && statusCode < 500) {
                return DeliveryResult.failure(
                        statusCode,
                        ErrorCategory.PERMANENT_BUSINESS,
                        new FailureCode("HTTP_" + statusCode + "_CLIENT_ERROR"),
                        new SafeErrorSummary("Target rejected request with client error HTTP " + statusCode + "."),
                        null
                );
            }

            // 5xx -> Transient Server Error
            return DeliveryResult.failure(
                    statusCode,
                    ErrorCategory.TRANSIENT_NETWORK,
                    new FailureCode("HTTP_" + statusCode + "_SERVER_ERROR"),
                    new SafeErrorSummary("Target service temporarily unavailable (HTTP " + statusCode + ")."),
                    null
            );

        } catch (HttpTimeoutException ex) {
            log.warn("Downstream read timeout dispatching message [{}]: {}", message.getId(), ex.getMessage());
            return DeliveryResult.ambiguousTimeout(
                    new FailureCode("DOWNSTREAM_READ_TIMEOUT"),
                    new SafeErrorSummary("Downstream target read timed out after dispatch; outcome is ambiguous.")
            );
        } catch (ConnectException ex) {
            log.warn("Connection refused or timed out for message [{}]: {}", message.getId(), ex.getMessage());
            return DeliveryResult.failure(
                    503,
                    ErrorCategory.TRANSIENT_NETWORK,
                    new FailureCode("CONNECT_EXCEPTION"),
                    new SafeErrorSummary("Failed to establish TCP connection to remote target."),
                    null
            );
        } catch (IOException ex) {
            log.warn("Network I/O failure delivering message [{}]: {}", message.getId(), ex.getMessage());
            return DeliveryResult.failure(
                    503,
                    ErrorCategory.TRANSIENT_NETWORK,
                    new FailureCode("NETWORK_IO_ERROR"),
                    new SafeErrorSummary("Network I/O error during message delivery: " + ex.getClass().getSimpleName()),
                    null
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("HTTP dispatch interrupted for message [{}]", message.getId());
            return DeliveryResult.failure(
                    500,
                    ErrorCategory.INTERNAL_UNEXPECTED,
                    new FailureCode("DELIVERY_INTERRUPTED"),
                    new SafeErrorSummary("Message delivery thread was interrupted."),
                    null
            );
        } catch (Exception ex) {
            log.error("Unexpected error during HTTP delivery for message [{}]: {}", message.getId(), ex.getMessage(), ex);
            return DeliveryResult.failure(
                    500,
                    ErrorCategory.INTERNAL_UNEXPECTED,
                    new FailureCode("UNEXPECTED_ERROR"),
                    new SafeErrorSummary("Unexpected error during HTTP delivery: " + ex.getClass().getSimpleName()),
                    null
            );
        }
    }

    private Duration parseRetryAfter(HttpResponse<?> response) {
        Optional<String> retryAfterHeader = response.headers().firstValue("Retry-After");
        if (retryAfterHeader.isPresent()) {
            try {
                long seconds = Long.parseLong(retryAfterHeader.get().trim());
                if (seconds > 0) {
                    return Duration.ofSeconds(seconds);
                }
            } catch (NumberFormatException ignored) {
                // If not numeric seconds, fallback to default
            }
        }
        return null;
    }
}
