package com.flowguard.core.application;

import com.flowguard.core.application.port.out.AuditRepositoryPort;
import com.flowguard.core.application.port.out.AuditRepositoryPort.AuditSearchCriteria;
import com.flowguard.core.application.port.out.AuditRepositoryPort.AuditSearchResult;
import com.flowguard.core.application.service.SearchAuditEventsService;
import com.flowguard.core.domain.model.AuditEvent;
import com.flowguard.core.domain.value.MessageId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SearchAuditEventsServiceTest {

    private AuditRepositoryPort auditRepository;
    private SearchAuditEventsService searchService;

    @BeforeEach
    void setUp() {
        auditRepository = mock(AuditRepositoryPort.class);
        searchService = new SearchAuditEventsService(auditRepository);
    }

    @Test
    @DisplayName("Searches audit events delegating criteria to AuditRepositoryPort")
    void testSearchAuditEvents() {
        MessageId msgId = MessageId.generate();
        AuditEvent event = new AuditEvent(
                UUID.randomUUID(),
                "admin-1",
                "MANUAL_RETRY",
                msgId,
                "Network recovered",
                Instant.now(),
                Map.of("target", "crm")
        );

        AuditSearchResult expectedResult = new AuditSearchResult(List.of(event), 1L, 0, 20);
        when(auditRepository.search(any(AuditSearchCriteria.class))).thenReturn(expectedResult);

        AuditSearchCriteria criteria = new AuditSearchCriteria(msgId, "admin-1", "MANUAL_RETRY", null, null, 0, 20);
        AuditSearchResult result = searchService.searchAuditEvents(criteria);

        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).actorId()).isEqualTo("admin-1");
        assertThat(result.events().get(0).action()).isEqualTo("MANUAL_RETRY");
        verify(auditRepository).search(criteria);
    }

    @Test
    @DisplayName("Handles null criteria by providing default values")
    void testSearchWithNullCriteria() {
        AuditSearchResult emptyResult = new AuditSearchResult(List.of(), 0L, 0, 20);
        when(auditRepository.search(any(AuditSearchCriteria.class))).thenReturn(emptyResult);

        AuditSearchResult result = searchService.searchAuditEvents(null);

        assertThat(result.totalElements()).isZero();
        assertThat(result.events()).isEmpty();
        verify(auditRepository).search(argThat(c -> c.page() == 0 && c.size() == 20));
    }
}
