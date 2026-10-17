package com.flowguard.core.domain.model;

import com.flowguard.core.domain.exception.DomainException;
import com.flowguard.core.domain.value.MessageId;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public record AuditEvent(
        UUID id,
        String actorId,
        String action,
        MessageId messageId,
        String reason,
        Instant createdAt,
        Map<String, String> metadata
) {
    public AuditEvent {
        if (id == null) throw new DomainException("AuditEvent ID cannot be null");
        if (actorId == null || actorId.isBlank()) throw new DomainException("Actor ID cannot be blank");
        if (action == null || action.isBlank()) throw new DomainException("Audit action cannot be blank");
        if (reason == null || reason.isBlank()) throw new DomainException("Audit reason cannot be blank");
        if (createdAt == null) throw new DomainException("CreatedAt cannot be null");
        metadata = metadata != null ? Map.copyOf(metadata) : Collections.emptyMap();
    }

    public static AuditEvent create(String actorId, String action, MessageId messageId, String reason, Map<String, String> metadata) {
        return new AuditEvent(
                UUID.randomUUID(),
                actorId.trim(),
                action.trim(),
                messageId,
                reason.trim(),
                Instant.now(),
                metadata
        );
    }
}
