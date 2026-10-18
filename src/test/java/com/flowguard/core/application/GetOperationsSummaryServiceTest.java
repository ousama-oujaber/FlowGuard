package com.flowguard.core.application;

import com.flowguard.core.application.port.in.GetOperationsSummaryUseCase.OperationsSummary;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.application.service.GetOperationsSummaryService;
import com.flowguard.core.domain.model.MessageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GetOperationsSummaryServiceTest {

    @Mock
    private MessageRepositoryPort messageRepository;

    private GetOperationsSummaryService service;

    @BeforeEach
    void setUp() {
        service = new GetOperationsSummaryService(messageRepository);
    }

    @Test
    @DisplayName("Aggregates all 7 status counts and computes oldest queue age")
    void aggregatesCountsAndComputesOldestAge() {
        when(messageRepository.countByStatus(MessageStatus.QUEUED)).thenReturn(10L);
        when(messageRepository.countByStatus(MessageStatus.PROCESSING)).thenReturn(2L);
        when(messageRepository.countByStatus(MessageStatus.SUCCEEDED)).thenReturn(100L);
        when(messageRepository.countByStatus(MessageStatus.RETRY_SCHEDULED)).thenReturn(3L);
        when(messageRepository.countByStatus(MessageStatus.DEAD_LETTERED)).thenReturn(1L);
        when(messageRepository.countByStatus(MessageStatus.RECONCILIATION_REQUIRED)).thenReturn(4L);
        when(messageRepository.countByStatus(MessageStatus.CANCELLED)).thenReturn(0L);

        Instant oldestTime = Instant.now().minusSeconds(120);
        when(messageRepository.findOldestQueuedCreatedAt()).thenReturn(Optional.of(oldestTime));

        OperationsSummary summary = service.getSummary();

        assertThat(summary.totalMessages()).isEqualTo(120L);
        assertThat(summary.countsByStatus().get(MessageStatus.QUEUED)).isEqualTo(10L);
        assertThat(summary.countsByStatus().get(MessageStatus.SUCCEEDED)).isEqualTo(100L);
        assertThat(summary.oldestQueuedAgeSeconds()).isGreaterThanOrEqualTo(120L);
        assertThat(summary.generatedAt()).isNotNull();

        verify(messageRepository, times(7)).countByStatus(any(MessageStatus.class));
        verify(messageRepository, times(1)).findOldestQueuedCreatedAt();
    }

    @Test
    @DisplayName("Oldest queue age is null when queue is empty")
    void emptyQueueReturnsNullOldestAge() {
        for (MessageStatus status : MessageStatus.values()) {
            when(messageRepository.countByStatus(status)).thenReturn(0L);
        }
        when(messageRepository.findOldestQueuedCreatedAt()).thenReturn(Optional.empty());

        OperationsSummary summary = service.getSummary();

        assertThat(summary.totalMessages()).isEqualTo(0L);
        assertThat(summary.oldestQueuedAgeSeconds()).isNull();
    }
}
