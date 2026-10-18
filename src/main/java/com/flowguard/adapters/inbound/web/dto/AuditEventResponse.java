package com.flowguard.adapters.inbound.web.dto;

import com.flowguard.core.domain.model.AuditEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,
        String actorId,
        String action,
        UUID messageId,
        String reason,
        Instant createdAt,
        Map<String, String> metadata
) {
    public static AuditEventResponse fromDomain(AuditEvent event) {
        return new AuditEventResponse(
                event.id(),
                event.actorId(),
                event.action(),
                event.messageId() != null ? event.messageId().value() : null,
                event.reason(),
                event.createdAt(),
                event.metadata()
        );
    }
}
