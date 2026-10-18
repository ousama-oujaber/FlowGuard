package com.flowguard.core.application;

import com.flowguard.core.application.port.out.AttemptRepositoryPort;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.application.service.GetAttemptsService;
import com.flowguard.core.domain.exception.DomainException;
import com.flowguard.core.domain.exception.MessageNotFoundException;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.model.ProcessingAttempt;
import com.flowguard.core.domain.value.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GetAttemptsServiceTest {

    @Mock
    private MessageRepositoryPort messageRepository;

    @Mock
    private AttemptRepositoryPort attemptRepository;

    private GetAttemptsService service;

    @BeforeEach
    void setUp() {
        service = new GetAttemptsService(messageRepository, attemptRepository);
    }

    @Test
    @DisplayName("Authorized client can retrieve monotonic attempt history for owned message")
    void authorizedClientRetrievesAttempts() {
        MessageId messageId = MessageId.generate();
        ClientId clientId = new ClientId("client_owner");

        IntegrationMessage message = IntegrationMessage.accept(
                clientId, new MessageType("ORDER_V1"), "SYS", new DestinationKey("DEST_1"),
                new CorrelationId("corr-1"), "REF", "{}", 262144, 5
        );

        ProcessingAttempt attempt1 = ProcessingAttempt.start(messageId, 1, "worker-1", "trace-1");
        attempt1.completeSuccess(200);

        when(messageRepository.findByIdAndClientId(messageId, clientId)).thenReturn(Optional.of(message));
        when(attemptRepository.findByMessageId(messageId)).thenReturn(List.of(attempt1));

        List<ProcessingAttempt> attempts = service.getAttempts(messageId, clientId);

        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);
        verify(messageRepository, times(1)).findByIdAndClientId(messageId, clientId);
        verify(attemptRepository, times(1)).findByMessageId(messageId);
    }

    @Test
    @DisplayName("Cross-client attempt lookup throws MessageNotFoundException to prevent information leakage")
    void unauthorizedCrossClientLookupDenied() {
        MessageId messageId = MessageId.generate();
        ClientId unauthorizedClient = new ClientId("client_intruder");

        // Message does not belong to intruder client
        when(messageRepository.findByIdAndClientId(messageId, unauthorizedClient)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAttempts(messageId, unauthorizedClient))
                .isInstanceOf(MessageNotFoundException.class)
                .hasMessageContaining("access denied for client");

        verify(attemptRepository, never()).findByMessageId(any());
    }

    @Test
    @DisplayName("Null messageId or clientId throws DomainException")
    void nullArgumentsThrowDomainException() {
        MessageId messageId = MessageId.generate();
        ClientId clientId = new ClientId("client_test");

        assertThatThrownBy(() -> service.getAttempts(null, clientId))
                .isInstanceOf(DomainException.class);

        assertThatThrownBy(() -> service.getAttempts(messageId, null))
                .isInstanceOf(DomainException.class);
    }
}
