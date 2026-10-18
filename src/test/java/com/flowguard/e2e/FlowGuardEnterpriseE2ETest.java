package com.flowguard.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowguard.adapters.inbound.web.dto.BulkSubmitItemRequest;
import com.flowguard.adapters.inbound.web.dto.BulkSubmitRequest;
import com.flowguard.core.application.port.in.ProcessMessagesUseCase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlowGuardEnterpriseE2ETest {

    static {
        System.setProperty("docker.client.strategy", "org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy");
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("flowguard_e2e")
            .withUsername("flowguard")
            .withPassword("flowguard");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        // Disable background polling to allow deterministic worker triggering in tests
        registry.add("flowguard.worker.enabled", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProcessMessagesUseCase processMessagesUseCase;

    @Autowired
    private ObjectMapper objectMapper;

    private String getBaseUrl() {
        return "http://localhost:" + port + "/api/v1";
    }

    private static String successfulMessageId;
    private static String timeoutMessageId;

    @Test
    @Order(1)
    @DisplayName("E2E Phase 1-2: Submit message, verify 202 Accepted, and assert Idempotency replay/conflict semantics")
    void testMessageSubmissionAndIdempotency() throws Exception {
        String idempotencyKey = "idemp-e2e-" + UUID.randomUUID();
        String payload = "{\"orderId\":\"ORD-9901\",\"amount\":199.99}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Client-Id", "client_acme");
        headers.set("X-Correlation-ID", "corr-e2e-001");

        Map<String, Object> body = Map.of(
                "messageType", "ORDER_CREATED",
                "destinationKey", "LEDGER_MAIN",
                "sourceSystem", "E-COMMERCE",
                "payload", Map.of("orderId", "ORD-9901", "amount", 199.99)
        );

        // 1. Initial Submission -> 202 Accepted
        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/messages",
                new HttpEntity<>(body, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation()).isNotNull();

        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.has("messageId")).isTrue();
        successfulMessageId = root.get("messageId").asText();
        assertThat(root.get("status").asText()).isEqualTo("QUEUED");
        assertThat(root.get("idempotentReplay").asBoolean()).isFalse();

        // 2. Duplicate Submission with Same Key & Payload -> 200 OK + idempotentReplay: true
        ResponseEntity<String> replayResponse = restTemplate.postForEntity(
                getBaseUrl() + "/messages",
                new HttpEntity<>(body, headers),
                String.class
        );

        assertThat(replayResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode replayNode = objectMapper.readTree(replayResponse.getBody());
        assertThat(replayNode.get("messageId").asText()).isEqualTo(successfulMessageId);
        assertThat(replayNode.get("idempotentReplay").asBoolean()).isTrue();

        // 3. Key Reuse with Altered Payload -> 409 Conflict (RFC 9457)
        Map<String, Object> alteredBody = Map.of(
                "messageType", "ORDER_CREATED",
                "destinationKey", "LEDGER_MAIN",
                "sourceSystem", "E-COMMERCE",
                "payload", Map.of("orderId", "ORD-9901", "amount", 99999.00) // Altered!
        );

        ResponseEntity<String> conflictResponse = restTemplate.postForEntity(
                getBaseUrl() + "/messages",
                new HttpEntity<>(alteredBody, headers),
                String.class
        );

        assertThat(conflictResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode conflictNode = objectMapper.readTree(conflictResponse.getBody());
        assertThat(conflictNode.get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        // 4. SSRF Defense: Rejection of Raw URL in destinationKey -> 400 Bad Request
        HttpHeaders ssrfHeaders = new HttpHeaders();
        ssrfHeaders.setContentType(MediaType.APPLICATION_JSON);
        ssrfHeaders.set("Idempotency-Key", "idemp-ssrf-" + UUID.randomUUID());
        ssrfHeaders.set("X-Client-Id", "client_acme");

        Map<String, Object> ssrfBody = Map.of(
                "messageType", "ORDER_CREATED",
                "destinationKey", "https://internal.vault.corp/api",
                "payload", Map.of("test", "ssrf")
        );

        ResponseEntity<String> ssrfResponse = restTemplate.postForEntity(
                getBaseUrl() + "/messages",
                new HttpEntity<>(ssrfBody, ssrfHeaders),
                String.class
        );

        assertThat(ssrfResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode ssrfNode = objectMapper.readTree(ssrfResponse.getBody());
        assertThat(ssrfNode.get("code").asText()).isEqualTo("INVALID_DESTINATION");
    }

    @Test
    @Order(2)
    @DisplayName("E2E Phase 3: Worker claim, execution to SUCCEEDED, and attempt history recording")
    void testWorkerProcessingAndAttemptHistory() throws Exception {
        assertThat(successfulMessageId).isNotNull();

        // Trigger worker polling cycle deterministically
        int processed = processMessagesUseCase.processDueMessages(10, "e2e-worker-node-1");
        assertThat(processed).isGreaterThanOrEqualTo(1);

        // Verify message status transitioned to SUCCEEDED
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Client-Id", "client_acme");

        ResponseEntity<String> getMsgResponse = restTemplate.exchange(
                getBaseUrl() + "/messages/" + successfulMessageId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(getMsgResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode msgNode = objectMapper.readTree(getMsgResponse.getBody());
        assertThat(msgNode.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(msgNode.get("attemptCount").asInt()).isEqualTo(1);

        // Verify monotonic attempt history
        ResponseEntity<String> attemptsResponse = restTemplate.exchange(
                getBaseUrl() + "/messages/" + successfulMessageId + "/attempts",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(attemptsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode attemptsNode = objectMapper.readTree(attemptsResponse.getBody());
        assertThat(attemptsNode.isArray()).isTrue();
        assertThat(attemptsNode.size()).isEqualTo(1);
        assertThat(attemptsNode.get(0).get("attemptNumber").asInt()).isEqualTo(1);
        assertThat(attemptsNode.get(0).get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(attemptsNode.get(0).get("workerId").asText()).isEqualTo("e2e-worker-node-1");
    }

    @Test
    @Order(3)
    @DisplayName("E2E Phase 4: Ambiguous timeout triggers RECONCILIATION_REQUIRED and halts auto-retries")
    void testAmbiguousTimeoutQuarantine() throws Exception {
        String idempotencyKey = "idemp-timeout-" + UUID.randomUUID();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Client-Id", "client_acme");

        // Destination with "TIMEOUT" triggers simulator ambiguous read timeout
        Map<String, Object> body = Map.of(
                "messageType", "BANK_SETTLEMENT",
                "destinationKey", "SIM_TIMEOUT_LEDGER",
                "payload", Map.of("settlementId", "SETTLE-01")
        );

        ResponseEntity<String> postResponse = restTemplate.postForEntity(
                getBaseUrl() + "/messages",
                new HttpEntity<>(body, headers),
                String.class
        );

        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        timeoutMessageId = objectMapper.readTree(postResponse.getBody()).get("messageId").asText();

        // Worker processes the message -> hits simulated timeout
        int processed = processMessagesUseCase.processDueMessages(10, "e2e-worker-node-2");
        assertThat(processed).isGreaterThanOrEqualTo(1);

        // Verify message transitioned to RECONCILIATION_REQUIRED
        ResponseEntity<String> checkResponse = restTemplate.exchange(
                getBaseUrl() + "/messages/" + timeoutMessageId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        JsonNode checkNode = objectMapper.readTree(checkResponse.getBody());
        assertThat(checkNode.get("status").asText()).isEqualTo("RECONCILIATION_REQUIRED");

        // Subsequent worker claim MUST NOT claim this message (quarantined against automatic retry)
        int secondPoll = processMessagesUseCase.processDueMessages(10, "e2e-worker-node-2");
        assertThat(secondPoll).isZero();
    }

    @Test
    @Order(4)
    @DisplayName("E2E Phase 5: Audited manual retry resets message to QUEUED and creates audit record")
    void testAuditedManualRetry() throws Exception {
        assertThat(timeoutMessageId).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Actor-Id", "ops-senior-engineer");

        Map<String, Object> retryBody = Map.of(
                "reason", "Target ledger investigated; duplicate record confirmed absent. Safe to re-drive."
        );

        ResponseEntity<String> retryResponse = restTemplate.postForEntity(
                getBaseUrl() + "/messages/" + timeoutMessageId + "/retries",
                new HttpEntity<>(retryBody, headers),
                String.class
        );

        assertThat(retryResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode retryResult = objectMapper.readTree(retryResponse.getBody());
        assertThat(retryResult.get("status").asText()).isEqualTo("QUEUED");
        String auditEventId = retryResult.get("auditEventId").asText();
        assertThat(auditEventId).isNotBlank();

        // Verify audit event is persisted and retrievable via GET /api/v1/audit-events
        HttpHeaders auditHeaders = new HttpHeaders();
        auditHeaders.set("X-Scope", "audit:read");

        ResponseEntity<String> auditQueryResponse = restTemplate.exchange(
                getBaseUrl() + "/audit-events?messageId=" + timeoutMessageId,
                HttpMethod.GET,
                new HttpEntity<>(auditHeaders),
                String.class
        );

        assertThat(auditQueryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode auditPage = objectMapper.readTree(auditQueryResponse.getBody());
        assertThat(auditPage.get("totalElements").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode firstEvent = auditPage.get("content").get(0);
        assertThat(firstEvent.get("actorId").asText()).isEqualTo("ops-senior-engineer");
        assertThat(firstEvent.get("action").asText()).isEqualTo("MESSAGE_RETRY_REQUESTED");
    }

    @Test
    @Order(5)
    @DisplayName("E2E Phase 8: Explicit message cancellation before worker dispatch")
    void testMessageCancellation() throws Exception {
        String idempotencyKey = "idemp-cancel-" + UUID.randomUUID();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Client-Id", "client_acme");

        Map<String, Object> body = Map.of(
                "messageType", "DISPATCH_ORDER",
                "destinationKey", "LEDGER_MAIN",
                "payload", Map.of("order", "CANCEL-ME")
        );

        ResponseEntity<String> submitResponse = restTemplate.postForEntity(
                getBaseUrl() + "/messages",
                new HttpEntity<>(body, headers),
                String.class
        );

        assertThat(submitResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String cancelMsgId = objectMapper.readTree(submitResponse.getBody()).get("messageId").asText();

        // Cancel the message
        HttpHeaders cancelHeaders = new HttpHeaders();
        cancelHeaders.setContentType(MediaType.APPLICATION_JSON);
        cancelHeaders.set("X-Actor-Id", "ops-supervisor");
        cancelHeaders.set("X-Client-Id", "client_acme");

        Map<String, Object> cancelBody = Map.of(
                "reason", "Customer cancelled before shipment."
        );

        ResponseEntity<String> cancelResponse = restTemplate.postForEntity(
                getBaseUrl() + "/messages/" + cancelMsgId + "/cancel",
                new HttpEntity<>(cancelBody, cancelHeaders),
                String.class
        );

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode cancelNode = objectMapper.readTree(cancelResponse.getBody());
        assertThat(cancelNode.get("status").asText()).isEqualTo("CANCELLED");

        // Worker claim cycle skips CANCELLED messages
        int processed = processMessagesUseCase.processDueMessages(10, "e2e-worker-cancel-test");
        // Only timeoutMessageId (from retry) should be processed, not the cancelled message!
        assertThat(processed).isLessThanOrEqualTo(1);

        // Verify status remains CANCELLED
        ResponseEntity<String> verifyResponse = restTemplate.exchange(
                getBaseUrl() + "/messages/" + cancelMsgId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        assertThat(objectMapper.readTree(verifyResponse.getBody()).get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    @Order(6)
    @DisplayName("E2E Phase 9: Bulk message ingestion with atomic acceptance and intra-batch replay")
    void testBulkMessageIngestion() throws Exception {
        String sharedKey = "batch-key-" + UUID.randomUUID();

        BulkSubmitItemRequest item1 = new BulkSubmitItemRequest(
                sharedKey,
                "INVOICE_PROCESS",
                "LEDGER_MAIN",
                "ERP",
                "batch-corr-1",
                "INV-101",
                Map.of("inv", 101)
        );

        BulkSubmitItemRequest item2 = new BulkSubmitItemRequest(
                "batch-key-unique-" + UUID.randomUUID(),
                "INVOICE_PROCESS",
                "LEDGER_MAIN",
                "ERP",
                "batch-corr-2",
                "INV-102",
                Map.of("inv", 102)
        );

        // Item 3 is an intra-batch duplicate of item 1 (same key + same payload -> replay)
        BulkSubmitItemRequest item3 = new BulkSubmitItemRequest(
                sharedKey,
                "INVOICE_PROCESS",
                "LEDGER_MAIN",
                "ERP",
                "batch-corr-3",
                "INV-101",
                Map.of("inv", 101)
        );

        BulkSubmitRequest bulkRequest = new BulkSubmitRequest(List.of(item1, item2, item3));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Client-Id", "client_acme");

        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/messages/batch",
                new HttpEntity<>(bulkRequest, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("totalCount").asInt()).isEqualTo(3);
        assertThat(root.get("acceptedCount").asInt()).isEqualTo(2);
        assertThat(root.get("replayedCount").asInt()).isEqualTo(1);
        assertThat(root.get("items").size()).isEqualTo(3);
    }

    @Test
    @Order(7)
    @DisplayName("E2E Operations & Telemetry: Operations summary, search pagination, and actuator endpoints")
    void testOperationsAndTelemetry() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Scope", "operations:read");

        // 1. Operations Summary
        ResponseEntity<String> summaryResponse = restTemplate.exchange(
                getBaseUrl() + "/operations/summary",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(summaryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode summaryNode = objectMapper.readTree(summaryResponse.getBody());
        assertThat(summaryNode.get("totalMessages").asLong()).isGreaterThanOrEqualTo(5);
        assertThat(summaryNode.has("countsByStatus")).isTrue();

        // 2. Search Messages with pagination
        HttpHeaders searchHeaders = new HttpHeaders();
        searchHeaders.set("X-Client-Id", "client_acme");

        ResponseEntity<String> searchResponse = restTemplate.exchange(
                getBaseUrl() + "/messages?page=0&size=5",
                HttpMethod.GET,
                new HttpEntity<>(searchHeaders),
                String.class
        );

        assertThat(searchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode searchNode = objectMapper.readTree(searchResponse.getBody());
        assertThat(searchNode.get("items").isArray()).isTrue();
        assertThat(searchNode.get("size").asInt()).isEqualTo(5);

        // 3. Actuator Health & Metrics
        ResponseEntity<String> healthResponse = restTemplate.getForEntity(
                "http://localhost:" + port + "/actuator/health",
                String.class
        );
        assertThat(healthResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(healthResponse.getBody()).contains("\"status\":\"UP\"");

        ResponseEntity<String> metricsResponse = restTemplate.getForEntity(
                "http://localhost:" + port + "/actuator/metrics",
                String.class
        );
        assertThat(metricsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metricsResponse.getBody()).contains("names");
    }
}
