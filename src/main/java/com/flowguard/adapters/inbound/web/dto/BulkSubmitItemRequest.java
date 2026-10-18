package com.flowguard.adapters.inbound.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BulkSubmitItemRequest(
        @NotBlank(message = "idempotencyKey is required")
        String idempotencyKey,

        @NotBlank(message = "messageType is required")
        String messageType,

        @NotBlank(message = "destinationKey is required")
        String destinationKey,

        String sourceSystem,

        String correlationId,

        String externalReference,

        @NotNull(message = "payload is required")
        Object payload
) {}
