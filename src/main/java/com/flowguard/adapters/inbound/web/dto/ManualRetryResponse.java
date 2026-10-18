package com.flowguard.adapters.inbound.web.dto;

import java.util.UUID;

public record ManualRetryResponse(
        UUID messageId,
        String status,
        UUID auditEventId,
        String message
) {}
