package com.flowguard.adapters.inbound.web;

import com.flowguard.core.application.port.in.SearchAuditEventsUseCase;
import com.flowguard.core.application.port.out.AuditRepositoryPort.AuditSearchCriteria;
import com.flowguard.core.application.port.out.AuditRepositoryPort.AuditSearchResult;
import com.flowguard.core.domain.model.AuditEvent;
import com.flowguard.core.domain.value.MessageId;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuditEventController.class)
class AuditEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SearchAuditEventsUseCase searchAuditEventsUseCase;

    @Test
    @DisplayName("GET /api/v1/audit-events returns 200 OK with paged audit events")
    void searchAuditEventsReturns200() throws Exception {
        UUID eventId = UUID.randomUUID();
        MessageId msgId = MessageId.generate();
        AuditEvent event = new AuditEvent(
                eventId,
                "ops-engineer",
                "MANUAL_RETRY",
                msgId,
                "Upstream gateway back online",
                Instant.parse("2026-10-18T00:00:00Z"),
                Map.of("target", "erp")
        );

        AuditSearchResult result = new AuditSearchResult(List.of(event), 1L, 0, 20);
        when(searchAuditEventsUseCase.searchAuditEvents(any(AuditSearchCriteria.class))).thenReturn(result);

        mockMvc.perform(get("/api/v1/audit-events")
                        .header("X-Scope", "audit:read")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].id").value(eventId.toString()))
                .andExpect(jsonPath("$.content[0].actorId").value("ops-engineer"))
                .andExpect(jsonPath("$.content[0].action").value("MANUAL_RETRY"))
                .andExpect(jsonPath("$.content[0].messageId").value(msgId.value().toString()))
                .andExpect(jsonPath("$.content[0].reason").value("Upstream gateway back online"));
    }

    @Test
    @DisplayName("GET /api/v1/audit-events with operations:read scope returns 200 OK")
    void searchAuditEventsWithOperationsScopeReturns200() throws Exception {
        AuditSearchResult result = new AuditSearchResult(List.of(), 0L, 0, 10);
        when(searchAuditEventsUseCase.searchAuditEvents(any(AuditSearchCriteria.class))).thenReturn(result);

        mockMvc.perform(get("/api/v1/audit-events")
                        .header("X-Scope", "operations:read")
                        .param("action", "MANUAL_RETRY")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/audit-events with unauthorized scope returns 403 Forbidden")
    void searchAuditEventsWithUnauthorizedScopeReturns403() throws Exception {
        mockMvc.perform(get("/api/v1/audit-events")
                        .header("X-Scope", "messages:write")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
