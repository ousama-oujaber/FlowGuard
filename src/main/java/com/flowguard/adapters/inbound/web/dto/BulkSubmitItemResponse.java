package com.flowguard.adapters.inbound.web.dto;

import java.time.Instant;
import java.util.UUID;

public record BulkSubmitItemResponse(
        int index,
        String idempotencyKey,
        UUID messageId,
        String status,
        String correlationId,
        Instant acceptedAt,
        boolean idempotentReplay
) {}
