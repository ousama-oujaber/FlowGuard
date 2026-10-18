package com.flowguard.adapters.inbound.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowguard.adapters.inbound.web.dto.SubmitMessageRequest;
import com.flowguard.core.application.port.in.GetMessageUseCase;
import com.flowguard.core.application.port.in.SubmitMessageUseCase;
import com.flowguard.core.application.port.in.SubmitMessageUseCase.SubmitResult;
import com.flowguard.core.domain.exception.IdempotencyConflictException;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.value.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MessageController.class)
class MessageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SubmitMessageUseCase submitMessageUseCase;

    @MockBean
    private GetMessageUseCase getMessageUseCase;

    @MockBean
    private com.flowguard.core.application.port.in.ManualRetryUseCase manualRetryUseCase;

    @MockBean
    private com.flowguard.core.application.port.in.SearchMessagesUseCase searchMessagesUseCase;

    @MockBean
    private com.flowguard.core.application.port.in.GetAttemptsUseCase getAttemptsUseCase;

    @MockBean
    private com.flowguard.core.application.port.in.CancelMessageUseCase cancelMessageUseCase;

    @Test
    @DisplayName("Valid submission returns 202 Accepted with Location header and envelope")
    void validSubmissionReturns202() throws Exception {
        UUID messageId = UUID.randomUUID();
        Instant now = Instant.now();

        when(submitMessageUseCase.submit(any())).thenReturn(
                new SubmitResult(new MessageId(messageId), MessageStatus.QUEUED, new CorrelationId("corr-123"), now, false)
        );

        SubmitMessageRequest request = new SubmitMessageRequest(
                "PAYROLL_V1",
                "LEDGER_MAIN",
                "HR_PORTAL",
                "EXT-01",
                Map.of("amount", 1250.00, "currency", "USD")
        );

        mockMvc.perform(post("/api/v1/messages")
                        .header("Idempotency-Key", "idemp-key-123")
                        .header("X-Correlation-ID", "corr-123")
                        .header("X-Client-Id", "client_payroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/messages/" + messageId))
                .andExpect(jsonPath("$.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.idempotentReplay").value(false))
                .andExpect(jsonPath("$.links.self").value("/api/v1/messages/" + messageId));
    }

    @Test
    @DisplayName("Missing Idempotency-Key header returns 400 with Problem Details code MISSING_IDEMPOTENCY_KEY")
    void missingIdempotencyKeyReturns400() throws Exception {
        SubmitMessageRequest request = new SubmitMessageRequest(
                "PAYROLL_V1",
                "LEDGER_MAIN",
                "HR_PORTAL",
                "EXT-01",
                Map.of("amount", 1250.00)
        );

        mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("DestinationKey containing URL scheme is rejected with 400 INVALID_DESTINATION (SSRF defense)")
    void ssrfUrlDestinationReturns400() throws Exception {
        SubmitMessageRequest request = new SubmitMessageRequest(
                "PAYROLL_V1",
                "http://169.254.169.254/latest/meta-data",
                "HR_PORTAL",
                "EXT-01",
                Map.of("amount", 1250.00)
        );

        mockMvc.perform(post("/api/v1/messages")
                        .header("Idempotency-Key", "idemp-key-ssrf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("INVALID_DESTINATION"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("Idempotency conflict returns 409 Conflict with Problem Details code IDEMPOTENCY_KEY_REUSED")
    void idempotencyConflictReturns409() throws Exception {
        when(submitMessageUseCase.submit(any())).thenThrow(
                new IdempotencyConflictException("The supplied key is already associated with a different request.")
        );

        SubmitMessageRequest request = new SubmitMessageRequest(
                "PAYROLL_V1",
                "LEDGER_MAIN",
                "HR_PORTAL",
                "EXT-01",
                Map.of("amount", 9999.00)
        );

        mockMvc.perform(post("/api/v1/messages")
                        .header("Idempotency-Key", "idemp-key-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("GET existing message returns 200 OK with message envelope")
    void getExistingMessageReturns200() throws Exception {
        UUID messageId = UUID.randomUUID();
        IntegrationMessage message = IntegrationMessage.accept(
                new ClientId("client_payroll"),
                new MessageType("PAYROLL_V1"),
                "HR_PORTAL",
                new DestinationKey("LEDGER_MAIN"),
                new CorrelationId("corr-123"),
                "EXT-01",
                "{\"amount\":100}",
                262144,
                5
        );

        when(getMessageUseCase.getMessage(any(), any())).thenReturn(Optional.of(message));

        mockMvc.perform(get("/api/v1/messages/" + messageId)
                        .header("X-Client-Id", "client_payroll"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageType").value("PAYROLL_V1"))
                .andExpect(jsonPath("$.destinationKey").value("LEDGER_MAIN"))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    @DisplayName("GET non-existing message returns 404 Not Found")
    void getNonExistingMessageReturns404() throws Exception {
        UUID messageId = UUID.randomUUID();
        when(getMessageUseCase.getMessage(any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/messages/" + messageId)
                        .header("X-Client-Id", "client_payroll"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST retry returns 202 Accepted when message is eligible")
    void retryEligibleMessageReturns202() throws Exception {
        UUID messageId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();

        when(manualRetryUseCase.retry(any())).thenReturn(
                new com.flowguard.core.application.port.in.ManualRetryUseCase.RetryResult(
                        new MessageId(messageId),
                        MessageStatus.QUEUED,
                        auditId,
                        "Manual retry requested successfully; message re-queued for processing."
                )
        );

        String body = """
            {
              "reason": "Target configuration corrected and confirmed safe to retry."
            }
            """;

        mockMvc.perform(post("/api/v1/messages/" + messageId + "/retries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "operator-ops-1")
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.auditEventId").value(auditId.toString()));
    }

    @Test
    @DisplayName("POST retry returns 409 Conflict when state transition is illegal")
    void retryIneligibleMessageReturns409() throws Exception {
        UUID messageId = UUID.randomUUID();

        when(manualRetryUseCase.retry(any())).thenThrow(
                new com.flowguard.core.domain.exception.InvalidStateTransitionException(
                        "Cannot manually retry message in status [SUCCEEDED]. Only DEAD_LETTERED or RECONCILIATION_REQUIRED messages are eligible."
                )
        );

        String body = """
            {
              "reason": "Retrying finished message."
            }
            """;

        mockMvc.perform(post("/api/v1/messages/" + messageId + "/retries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "operator-ops-1")
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST retry returns 400 Bad Request when reason is missing or blank")
    void retryMissingReasonReturns400() throws Exception {
        UUID messageId = UUID.randomUUID();

        String body = """
            {
              "reason": ""
            }
            """;

        mockMvc.perform(post("/api/v1/messages/" + messageId + "/retries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "operator-ops-1")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /api/v1/messages performs scoped search with pagination and returns 200 OK")
    void searchMessagesReturns200() throws Exception {
        IntegrationMessage message = IntegrationMessage.accept(
                new ClientId("client_payroll"),
                new MessageType("PAYROLL_V1"),
                "HR_PORTAL",
                new DestinationKey("LEDGER_MAIN"),
                new CorrelationId("corr-123"),
                "EXT-01",
                "{\"amount\":100}",
                262144,
                5
        );

        when(searchMessagesUseCase.search(any())).thenReturn(
                new com.flowguard.core.application.port.in.SearchMessagesUseCase.SearchResult(
                        List.of(message),
                        0,
                        20,
                        1L,
                        1
                )
        );

        mockMvc.perform(get("/api/v1/messages")
                        .header("X-Client-Id", "client_payroll")
                        .param("status", "QUEUED")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items[0].messageType").value("PAYROLL_V1"));
    }

    @Test
    @DisplayName("GET /api/v1/messages/{id}/attempts returns attempt history ordered")
    void getAttemptsReturns200() throws Exception {
        UUID messageId = UUID.randomUUID();
        com.flowguard.core.domain.model.ProcessingAttempt attempt =
                com.flowguard.core.domain.model.ProcessingAttempt.start(
                        new MessageId(messageId), 1, "worker-1", "trace-abc"
                );
        attempt.completeSuccess(200);

        when(getAttemptsUseCase.getAttempts(any(), any())).thenReturn(List.of(attempt));

        mockMvc.perform(get("/api/v1/messages/" + messageId + "/attempts")
                        .header("X-Client-Id", "client_payroll"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attemptNumber").value(1))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].workerId").value("worker-1"))
                .andExpect(jsonPath("$[0].targetStatusCode").value(200));
    }

    @Test
    @DisplayName("GET /api/v1/messages/{id} includes payload when caller has messages:payload:read scope")
    void getMessageIncludesPayloadWhenAuthorized() throws Exception {
        UUID messageId = UUID.randomUUID();
        IntegrationMessage message = IntegrationMessage.accept(
                new ClientId("client_payroll"),
                new MessageType("PAYROLL_V1"),
                "HR_PORTAL",
                new DestinationKey("LEDGER_MAIN"),
                new CorrelationId("corr-123"),
                "EXT-01",
                "{\"amount\":100}",
                262144,
                5
        );

        when(getMessageUseCase.getMessage(any(), any())).thenReturn(Optional.of(message));

        mockMvc.perform(get("/api/v1/messages/" + messageId)
                        .header("X-Client-Id", "client_payroll")
                        .header("X-Scope", "messages:payload:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.amount").value(100));
    }

    @Test
    @DisplayName("POST /api/v1/messages/{id}/cancel cancels message before side effects start")
    void cancelMessageReturns202() throws Exception {
        UUID messageId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();

        when(cancelMessageUseCase.cancelMessage(eq(messageId), any(), eq("Duplicate submission"), any()))
                .thenReturn(new com.flowguard.core.application.port.in.CancelMessageUseCase.CancelResult(
                        messageId,
                        com.flowguard.core.domain.model.MessageStatus.CANCELLED,
                        auditId,
                        "Message successfully cancelled before dispatch side-effects."
                ));

        mockMvc.perform(post("/api/v1/messages/" + messageId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "ops-user")
                        .header("X-Client-Id", "client_acme")
                        .content("{\"reason\": \"Duplicate submission\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.auditEventId").value(auditId.toString()));
    }
}
