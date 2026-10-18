package com.flowguard.core.application;

import com.flowguard.core.application.port.out.AttemptRepositoryPort;
import com.flowguard.core.application.port.out.DeliveryPort;
import com.flowguard.core.application.port.out.DeliveryPort.DeliveryResult;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.application.service.ProcessMessagesService;
import com.flowguard.core.domain.model.AttemptStatus;
import com.flowguard.core.domain.model.ErrorCategory;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.model.ProcessingAttempt;
import com.flowguard.core.domain.value.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcessMessagesServiceTest {

    @Mock
    private MessageRepositoryPort messageRepository;

    @Mock
    private AttemptRepositoryPort attemptRepository;

    @Mock
    private DeliveryPort deliveryPort;

    private ProcessMessagesService service;

    @BeforeEach
    void setUp() {
        service = new ProcessMessagesService(
                messageRepository,
                attemptRepository,
                deliveryPort,
                5,
                1,
                10,
                2.0,
                false,
                60
        );
    }

    @Test
    @DisplayName("Claimed message delivered successfully transitions to SUCCEEDED with completed attempt")
    void claimAndProcessSuccess() {
        IntegrationMessage message = createQueuedMessage();
        LeaseToken claimToken = LeaseToken.generate();
        message.claim("worker-01", claimToken, Duration.ofMinutes(1));

        when(messageRepository.claimDueMessages(anyInt(), anyString(), any(), any()))
                .thenReturn(List.of(message));
        when(deliveryPort.deliver(any())).thenReturn(DeliveryResult.success(200));
        when(messageRepository.updateStatusWithLeaseFence(any(), any())).thenReturn(true);

        int processed = service.processDueMessages(10, "worker-01");

        assertThat(processed).isEqualTo(1);
        assertThat(message.getStatus()).isEqualTo(MessageStatus.SUCCEEDED);

        verify(deliveryPort, times(1)).deliver(message);
        verify(attemptRepository, times(2)).save(any(ProcessingAttempt.class));
        verify(messageRepository, times(1)).updateStatusWithLeaseFence(eq(message), any(LeaseToken.class));
    }

    @Test
    @DisplayName("Transient delivery error transitions message to RETRY_SCHEDULED")
    void claimAndProcessTransientFailure() {
        IntegrationMessage message = createQueuedMessage();
        LeaseToken claimToken = LeaseToken.generate();
        message.claim("worker-01", claimToken, Duration.ofMinutes(1));

        when(messageRepository.claimDueMessages(anyInt(), anyString(), any(), any()))
                .thenReturn(List.of(message));
        when(deliveryPort.deliver(any())).thenReturn(
                DeliveryResult.failure(504, ErrorCategory.TRANSIENT_NETWORK, new FailureCode("TIMEOUT"), new SafeErrorSummary("Peer timed out"), null)
        );
        when(messageRepository.updateStatusWithLeaseFence(any(), any())).thenReturn(true);

        service.processDueMessages(10, "worker-01");

        assertThat(message.getStatus()).isEqualTo(MessageStatus.RETRY_SCHEDULED);
        assertThat(message.getNextAttemptAt()).isNotNull();

        ArgumentCaptor<ProcessingAttempt> attemptCaptor = ArgumentCaptor.forClass(ProcessingAttempt.class);
        verify(attemptRepository, atLeastOnce()).save(attemptCaptor.capture());

        ProcessingAttempt finalizedAttempt = attemptCaptor.getValue();
        assertThat(finalizedAttempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(finalizedAttempt.getRetryable()).isTrue();
    }

    @Test
    @DisplayName("Permanent delivery error immediately transitions message to DEAD_LETTERED")
    void claimAndProcessPermanentFailure() {
        IntegrationMessage message = createQueuedMessage();
        LeaseToken claimToken = LeaseToken.generate();
        message.claim("worker-01", claimToken, Duration.ofMinutes(1));

        when(messageRepository.claimDueMessages(anyInt(), anyString(), any(), any()))
                .thenReturn(List.of(message));
        when(deliveryPort.deliver(any())).thenReturn(
                DeliveryResult.failure(422, ErrorCategory.PERMANENT_BUSINESS, new FailureCode("BAD_DATA"), new SafeErrorSummary("Unprocessable"), null)
        );
        when(messageRepository.updateStatusWithLeaseFence(any(), any())).thenReturn(true);

        service.processDueMessages(10, "worker-01");

        assertThat(message.getStatus()).isEqualTo(MessageStatus.DEAD_LETTERED);
    }

    @Test
    @DisplayName("Ambiguous socket timeout transitions message to RECONCILIATION_REQUIRED without retry")
    void claimAndProcessAmbiguousTimeout() {
        IntegrationMessage message = createQueuedMessage();
        LeaseToken claimToken = LeaseToken.generate();
        message.claim("worker-01", claimToken, Duration.ofMinutes(1));

        when(messageRepository.claimDueMessages(anyInt(), anyString(), any(), any()))
                .thenReturn(List.of(message));
        when(deliveryPort.deliver(any())).thenReturn(
                DeliveryResult.ambiguousTimeout(new FailureCode("SOCKET_TIMEOUT"), new SafeErrorSummary("Timeout after write"))
        );
        when(messageRepository.updateStatusWithLeaseFence(any(), any())).thenReturn(true);

        service.processDueMessages(10, "worker-01");

        assertThat(message.getStatus()).isEqualTo(MessageStatus.RECONCILIATION_REQUIRED);
        assertThat(message.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("Stale worker result update rejected by lease fencing is handled cleanly")
    void staleWorkerLeaseFencing() {
        IntegrationMessage message = createQueuedMessage();
        LeaseToken claimToken = LeaseToken.generate();
        message.claim("worker-01", claimToken, Duration.ofMinutes(1));

        when(messageRepository.claimDueMessages(anyInt(), anyString(), any(), any()))
                .thenReturn(List.of(message));
        when(deliveryPort.deliver(any())).thenReturn(DeliveryResult.success(200));
        // Simulate lease fence returning false (superseded)
        when(messageRepository.updateStatusWithLeaseFence(any(), any())).thenReturn(false);

        int processed = service.processDueMessages(10, "worker-01");

        assertThat(processed).isEqualTo(1);
        verify(messageRepository, times(1)).updateStatusWithLeaseFence(eq(message), any(LeaseToken.class));
    }

    private IntegrationMessage createQueuedMessage() {
        return IntegrationMessage.accept(
                new ClientId("client_payroll"),
                new MessageType("PAYROLL_ENTRY_V1"),
                "HR_PORTAL",
                new DestinationKey("LEDGER_MAIN"),
                new CorrelationId("corr-123"),
                "EXT-01",
                "{\"amount\":100}",
                262144,
                5
        );
    }
}
