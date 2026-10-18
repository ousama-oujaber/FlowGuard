package com.flowguard.core.application.service;

import com.flowguard.core.application.port.in.GetOperationsSummaryUseCase;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.domain.model.MessageStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

@Service
public class GetOperationsSummaryService implements GetOperationsSummaryUseCase {

    private final MessageRepositoryPort messageRepository;

    public GetOperationsSummaryService(MessageRepositoryPort messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public OperationsSummary getSummary() {
        Map<MessageStatus, Long> counts = new EnumMap<>(MessageStatus.class);
        long total = 0L;

        for (MessageStatus status : MessageStatus.values()) {
            long count = messageRepository.countByStatus(status);
            counts.put(status, count);
            total += count;
        }

        Optional<Instant> oldestQueued = messageRepository.findOldestQueuedCreatedAt();
        Long oldestAgeSeconds = oldestQueued
                .map(created -> Math.max(0, Duration.between(created, Instant.now()).toSeconds()))
                .orElse(null);

        return new OperationsSummary(counts, total, oldestAgeSeconds, Instant.now());
    }
}
