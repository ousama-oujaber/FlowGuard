package com.flowguard.core.application;

import com.flowguard.core.application.port.in.RecoverExpiredLeasesUseCase;
import com.flowguard.core.application.port.out.AttemptRepositoryPort;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.application.service.RecoverExpiredLeasesService;
import com.flowguard.core.domain.model.AttemptStatus;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.model.ProcessingAttempt;
import com.flowguard.core.domain.value.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoverExpiredLeasesServiceTest {

    @Mock
    private MessageRepositoryPort messageRepository;

    @Mock
    private AttemptRepositoryPort attemptRepository;

    private RecoverExpiredLeasesService service;

    @BeforeEach
    void setUp() {
        service = new RecoverExpiredLeasesService(messageRepository, attemptRepository, 3);
    }

    @Test
    @DisplayName("Empty list of expired messages returns 0 recovery counts")
    void emptyExpiredMessages() {
        when(messageRepository.findExpiredLeases(any(), anyInt())).thenReturn(Collections.emptyList());

        RecoverExpiredLeasesUseCase.RecoveryResult result = service.recover(10, Duration.ofMinutes(1));

        assertThat(result.recoveredCount()).isEqualTo(0);
        assertThat(result.deadLetteredCount()).isEqualTo(0);
        assertThat(result.reconciliationCount()).isEqualTo(0);
        verify(messageRepository, never()).updateStatusWithLeaseFence(any(), any());
    }

    @Test
    @DisplayName("Expired message with attempts < max transitions to RECONCILIATION_REQUIRED and closes attempt")
    void expiredMessageRoutesToReconciliation() {
        IntegrationMessage message = IntegrationMessage.accept(
                new ClientId("client_a"),
                new MessageType("TYPE_A"),
                "SYS",
                new DestinationKey("DEST_A"),
                new CorrelationId("corr-1"),
                "REF",
                "{\"foo\":\"bar\"}",
                262144,
                3
        );
        LeaseToken token = LeaseToken.generate();
        message.claim("worker-crashed", token, Duration.ofSeconds(1));

        ProcessingAttempt attempt = ProcessingAttempt.start(message.getId(), 1, "worker-crashed", "trace-1");

        when(messageRepository.findExpiredLeases(any(), anyInt())).thenReturn(List.of(message));
        when(attemptRepository.findByMessageId(message.getId())).thenReturn(List.of(attempt));
        when(messageRepository.updateStatusWithLeaseFence(eq(message), eq(token))).thenReturn(true);

        RecoverExpiredLeasesUseCase.RecoveryResult result = service.recover(10, Duration.ofMinutes(1));

        assertThat(result.recoveredCount()).isEqualTo(1);
        assertThat(result.reconciliationCount()).isEqualTo(1);
        assertThat(result.deadLetteredCount()).isEqualTo(0);

        assertThat(message.getStatus()).isEqualTo(MessageStatus.RECONCILIATION_REQUIRED);
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.UNKNOWN);
        verify(attemptRepository, times(1)).save(attempt);
        verify(messageRepository, times(1)).updateStatusWithLeaseFence(message, token);
    }

    @Test
    @DisplayName("Expired message with attempts >= max transitions to DEAD_LETTERED")
    void expiredMessageExhaustedAttemptsRoutesToDeadLetter() {
        IntegrationMessage message = IntegrationMessage.accept(
                new ClientId("client_a"),
                new MessageType("TYPE_A"),
                "SYS",
                new DestinationKey("DEST_A"),
                new CorrelationId("corr-1"),
                "REF",
                "{\"foo\":\"bar\"}",
                262144,
                1 // max attempts = 1
        );
        LeaseToken token = LeaseToken.generate();
        message.claim("worker-crashed", token, Duration.ofSeconds(1));

        ProcessingAttempt attempt = ProcessingAttempt.start(message.getId(), 1, "worker-crashed", "trace-1");

        when(messageRepository.findExpiredLeases(any(), anyInt())).thenReturn(List.of(message));
        when(attemptRepository.findByMessageId(message.getId())).thenReturn(List.of(attempt));
        when(messageRepository.updateStatusWithLeaseFence(eq(message), eq(token))).thenReturn(true);

        RecoverExpiredLeasesUseCase.RecoveryResult result = service.recover(10, Duration.ofMinutes(1));

        assertThat(result.recoveredCount()).isEqualTo(1);
        assertThat(result.deadLetteredCount()).isEqualTo(1);
        assertThat(result.reconciliationCount()).isEqualTo(0);

        assertThat(message.getStatus()).isEqualTo(MessageStatus.DEAD_LETTERED);
    }

    @Test
    @DisplayName("When lease fence rejects update, recovery count is not incremented")
    void leaseFenceRejectionIgnored() {
        IntegrationMessage message = IntegrationMessage.accept(
                new ClientId("client_a"),
                new MessageType("TYPE_A"),
                "SYS",
                new DestinationKey("DEST_A"),
                new CorrelationId("corr-1"),
                "REF",
                "{\"foo\":\"bar\"}",
                262144,
                3
        );
        LeaseToken token = LeaseToken.generate();
        message.claim("worker-crashed", token, Duration.ofSeconds(1));

        when(messageRepository.findExpiredLeases(any(), anyInt())).thenReturn(List.of(message));
        when(attemptRepository.findByMessageId(message.getId())).thenReturn(Collections.emptyList());
        when(messageRepository.updateStatusWithLeaseFence(eq(message), eq(token))).thenReturn(false);

        RecoverExpiredLeasesUseCase.RecoveryResult result = service.recover(10, Duration.ofMinutes(1));

        assertThat(result.recoveredCount()).isEqualTo(0);
    }
}
