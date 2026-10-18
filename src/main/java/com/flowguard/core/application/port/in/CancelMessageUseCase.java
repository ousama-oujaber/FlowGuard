package com.flowguard.core.application.port.in;

import com.flowguard.core.domain.model.MessageStatus;

import java.util.UUID;

/**
 * Input port for explicit cancellation of in-queue messages prior to external dispatch.
 */
public interface CancelMessageUseCase {

    record CancelResult(
            UUID messageId,
            MessageStatus status,
            UUID auditEventId,
            String message
    ) {}

    CancelResult cancelMessage(UUID messageId, String actorId, String reason, String clientScope);
}
