package com.flowguard.adapters.outbound.http;

import com.flowguard.core.application.port.out.DeliveryPort.DeliveryResult;
import com.flowguard.core.domain.model.ErrorCategory;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.value.ClientId;
import com.flowguard.core.domain.value.CorrelationId;
import com.flowguard.core.domain.value.DestinationKey;
import com.flowguard.core.domain.value.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HttpDeliveryAdapterTest {

    @Mock
    private HttpClient httpClient;

    private DestinationRegistry destinationRegistry;
    private HttpDeliveryAdapter adapter;

    @BeforeEach
    void setUp() {
        destinationRegistry = new DestinationRegistry();
        destinationRegistry.register("TEST_TARGET", new DestinationRegistry.DestinationConfig(
                "https://api.test.corp/v1/deliver",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Map.of("X-Test-Header", "true")
        ));
        adapter = new HttpDeliveryAdapter(destinationRegistry, httpClient);
    }

    private IntegrationMessage createMessage(String destinationKey) {
        return IntegrationMessage.accept(
                new ClientId("client-acme"),
                new MessageType("PAYMENT_V1"),
                "SRC_APP",
                new DestinationKey(destinationKey),
                new CorrelationId("corr-123"),
                "EXT-999",
                "{\"amount\": 100.00}",
                262144,
                3
        );
    }

    @Test
    @DisplayName("HTTP 200 response yields successful DeliveryResult")
    void deliversSuccessfully() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        DeliveryResult result = adapter.deliver(createMessage("TEST_TARGET"));

        assertThat(result.success()).isTrue();
        assertThat(result.targetStatusCode()).isEqualTo(200);
        assertThat(result.errorCategory()).isNull();
    }

    @Test
    @DisplayName("HTTP 429 Too Many Requests parses Retry-After header")
    void handlesRateLimitWithRetryAfter() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        HttpHeaders headers = HttpHeaders.of(Map.of("Retry-After", List.of("15")), (k, v) -> true);
        when(response.statusCode()).thenReturn(429);
        when(response.headers()).thenReturn(headers);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        DeliveryResult result = adapter.deliver(createMessage("TEST_TARGET"));

        assertThat(result.success()).isFalse();
        assertThat(result.targetStatusCode()).isEqualTo(429);
        assertThat(result.errorCategory()).isEqualTo(ErrorCategory.REMOTE_RATE_LIMIT);
        assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("HTTP 422 Unprocessable Entity returns permanent business error")
    void handlesClientError() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(422);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        DeliveryResult result = adapter.deliver(createMessage("TEST_TARGET"));

        assertThat(result.success()).isFalse();
        assertThat(result.targetStatusCode()).isEqualTo(422);
        assertThat(result.errorCategory()).isEqualTo(ErrorCategory.PERMANENT_BUSINESS);
    }

    @Test
    @DisplayName("HTTP 503 Service Unavailable returns transient network error")
    void handlesTransientServerError() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        DeliveryResult result = adapter.deliver(createMessage("TEST_TARGET"));

        assertThat(result.success()).isFalse();
        assertThat(result.targetStatusCode()).isEqualTo(503);
        assertThat(result.errorCategory()).isEqualTo(ErrorCategory.TRANSIENT_NETWORK);
    }

    @Test
    @DisplayName("Socket read timeout (HttpTimeoutException) produces ambiguous timeout for reconciliation")
    void handlesAmbiguousTimeout() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("Read timed out after 5000ms"));

        DeliveryResult result = adapter.deliver(createMessage("TEST_TARGET"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ErrorCategory.REMOTE_TIMEOUT_UNKNOWN);
        assertThat(result.errorCode().value()).isEqualTo("DOWNSTREAM_READ_TIMEOUT");
    }

    @Test
    @DisplayName("TCP ConnectException produces transient network failure")
    void handlesConnectException() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new ConnectException("Connection refused"));

        DeliveryResult result = adapter.deliver(createMessage("TEST_TARGET"));

        assertThat(result.success()).isFalse();
        assertThat(result.targetStatusCode()).isEqualTo(503);
        assertThat(result.errorCategory()).isEqualTo(ErrorCategory.TRANSIENT_NETWORK);
    }

    @Test
    @DisplayName("Unregistered destination key rejects request with CONFIGURATION_FAULT")
    void rejectsUnregisteredDestination() {
        DeliveryResult result = adapter.deliver(createMessage("UNREGISTERED_KEY"));

        assertThat(result.success()).isFalse();
        assertThat(result.targetStatusCode()).isEqualTo(400);
        assertThat(result.errorCategory()).isEqualTo(ErrorCategory.CONFIGURATION_FAULT);
    }
}
