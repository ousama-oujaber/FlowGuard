package com.flowguard.core.application.port.out;

import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.value.ClientId;
import com.flowguard.core.domain.value.LeaseToken;
import com.flowguard.core.domain.value.MessageId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MessageRepositoryPort {

    void save(IntegrationMessage message);

    Optional<IntegrationMessage> findById(MessageId id);

    Optional<IntegrationMessage> findByIdAndClientId(MessageId id, ClientId clientId);

    List<IntegrationMessage> claimDueMessages(int batchSize, String workerId, LeaseToken token, Instant leaseUntil);

    boolean updateStatusWithLeaseFence(IntegrationMessage message, LeaseToken leaseToken);

    long countByStatus(MessageStatus status);

    void delete(MessageId id);

    List<IntegrationMessage> findExpiredLeases(Instant cutoff, int batchSize);

    com.flowguard.core.application.port.in.SearchMessagesUseCase.SearchResult search(
            com.flowguard.core.application.port.in.SearchMessagesUseCase.SearchCriteria criteria
    );

    Optional<Instant> findOldestQueuedCreatedAt();
}

