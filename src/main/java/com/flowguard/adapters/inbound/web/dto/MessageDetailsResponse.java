package com.flowguard.adapters.inbound.web.dto;

import com.flowguard.core.domain.model.IntegrationMessage;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MessageDetailsResponse(
        UUID messageId,
        String clientId,
        String messageType,
        String sourceSystem,
        String destinationKey,
        String externalReference,
        String correlationId,
        String status,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        String lastErrorCode,
        String lastErrorSummary,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Map<String, String> links
) {
    public static MessageDetailsResponse fromDomain(IntegrationMessage message) {
        String selfLink = "/api/v1/messages/" + message.getId().value();
        String attemptsLink = "/api/v1/messages/" + message.getId().value() + "/attempts";

        return new MessageDetailsResponse(
                message.getId().value(),
                message.getClientId().value(),
                message.getMessageType().value(),
                message.getSourceSystem(),
                message.getDestinationKey().value(),
                message.getExternalReference(),
                message.getCorrelationId().value(),
                message.getStatus().name(),
                message.getAttemptCount(),
                message.getMaxAttempts(),
                message.getNextAttemptAt(),
                message.getLastErrorCode() != null ? message.getLastErrorCode().value() : null,
                message.getLastErrorSummary() != null ? message.getLastErrorSummary().value() : null,
                message.getCreatedAt(),
                message.getUpdatedAt(),
                message.getCompletedAt(),
                Map.of("self", selfLink, "attempts", attemptsLink)
        );
    }
}
