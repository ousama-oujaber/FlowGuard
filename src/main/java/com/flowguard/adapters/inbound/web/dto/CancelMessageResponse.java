package com.flowguard.adapters.inbound.web.dto;

import com.flowguard.core.domain.model.MessageStatus;

import java.util.UUID;

public record CancelMessageResponse(
        UUID messageId,
        MessageStatus status,
        UUID auditEventId,
        String message
) {}
