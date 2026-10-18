package com.flowguard.adapters.inbound.web;

import com.flowguard.core.application.port.in.GetOperationsSummaryUseCase;
import com.flowguard.core.application.port.in.GetOperationsSummaryUseCase.OperationsSummary;
import com.flowguard.core.domain.model.MessageStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OperationsController.class)
class OperationsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GetOperationsSummaryUseCase getOperationsSummaryUseCase;

    @Test
    @DisplayName("GET /api/v1/operations/summary returns 200 OK with status counts and oldest age")
    void getOperationsSummaryReturns200() throws Exception {
        Map<MessageStatus, Long> counts = Map.of(
                MessageStatus.QUEUED, 12L,
                MessageStatus.PROCESSING, 3L,
                MessageStatus.SUCCEEDED, 500L,
                MessageStatus.RETRY_SCHEDULED, 5L,
                MessageStatus.DEAD_LETTERED, 2L,
                MessageStatus.RECONCILIATION_REQUIRED, 1L,
                MessageStatus.CANCELLED, 0L
        );

        OperationsSummary summary = new OperationsSummary(
                counts,
                523L,
                45L,
                Instant.now()
        );

        when(getOperationsSummaryUseCase.getSummary()).thenReturn(summary);

        mockMvc.perform(get("/api/v1/operations/summary")
                        .header("X-Scope", "operations:read")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMessages").value(523))
                .andExpect(jsonPath("$.oldestQueuedAgeSeconds").value(45))
                .andExpect(jsonPath("$.countsByStatus.QUEUED").value(12))
                .andExpect(jsonPath("$.countsByStatus.SUCCEEDED").value(500))
                .andExpect(jsonPath("$.countsByStatus.DEAD_LETTERED").value(2));
    }
}
