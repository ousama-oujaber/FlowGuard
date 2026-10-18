package com.flowguard.core.application.port.in;

import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.value.ClientId;
import com.flowguard.core.domain.value.CorrelationId;
import com.flowguard.core.domain.value.DestinationKey;
import com.flowguard.core.domain.value.MessageType;

import java.time.Instant;
import java.util.List;

public interface SearchMessagesUseCase {

    record SearchCriteria(
            ClientId clientId,
            MessageStatus status,
            MessageType messageType,
            DestinationKey destinationKey,
            CorrelationId correlationId,
            Instant createdAfter,
            Instant createdBefore,
            int page,
            int size
    ) {
        public SearchCriteria {
            page = Math.max(0, page);
            size = size <= 0 ? 20 : Math.min(size, 100);
        }
    }

    record SearchResult(
            List<IntegrationMessage> items,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}

    SearchResult search(SearchCriteria criteria);
}
