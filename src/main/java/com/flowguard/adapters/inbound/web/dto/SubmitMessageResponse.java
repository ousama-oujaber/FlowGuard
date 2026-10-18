package com.flowguard.adapters.inbound.web.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SubmitMessageResponse(
        UUID messageId,
        String status,
        String correlationId,
        Instant acceptedAt,
        boolean idempotentReplay,
        Map<String, String> links
) {
    public static SubmitMessageResponse of(UUID messageId, String status, String correlationId, Instant acceptedAt, boolean idempotentReplay) {
        String selfLink = "/api/v1/messages/" + messageId;
        String attemptsLink = "/api/v1/messages/" + messageId + "/attempts";
        return new SubmitMessageResponse(
                messageId,
                status,
                correlationId,
                acceptedAt,
                idempotentReplay,
                Map.of("self", selfLink, "attempts", attemptsLink)
        );
    }
}
