package com.flowguard.core.application;

import com.flowguard.core.application.port.in.SearchMessagesUseCase;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.application.service.SearchMessagesService;
import com.flowguard.core.domain.exception.DomainException;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.value.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchMessagesServiceTest {

    @Mock
    private MessageRepositoryPort messageRepository;

    private SearchMessagesService service;

    @BeforeEach
    void setUp() {
        service = new SearchMessagesService(messageRepository);
    }

    @Test
    @DisplayName("Search delegates criteria to repository and returns search result")
    void searchDelegatesToRepository() {
        ClientId clientId = new ClientId("client_acme");
        SearchMessagesUseCase.SearchCriteria criteria = new SearchMessagesUseCase.SearchCriteria(
                clientId, MessageStatus.QUEUED, new MessageType("ORDER_V1"), null, null, null, null, 0, 20
        );

        IntegrationMessage message = IntegrationMessage.accept(
                clientId, new MessageType("ORDER_V1"), "SYS", new DestinationKey("DEST_1"),
                new CorrelationId("corr-1"), "REF", "{}", 262144, 5
        );

        SearchMessagesUseCase.SearchResult expected = new SearchMessagesUseCase.SearchResult(
                List.of(message), 0, 20, 1L, 1
        );

        when(messageRepository.search(criteria)).thenReturn(expected);

        SearchMessagesUseCase.SearchResult actual = service.search(criteria);

        assertThat(actual).isEqualTo(expected);
        verify(messageRepository, times(1)).search(criteria);
    }

    @Test
    @DisplayName("Search throws DomainException when criteria or clientId is null")
    void searchValidatesCriteria() {
        assertThatThrownBy(() -> service.search(null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("SearchCriteria cannot be null");

        SearchMessagesUseCase.SearchCriteria nullClient = new SearchMessagesUseCase.SearchCriteria(
                null, MessageStatus.QUEUED, null, null, null, null, null, 0, 20
        );

        assertThatThrownBy(() -> service.search(nullClient))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("clientId is mandatory");
    }
}
