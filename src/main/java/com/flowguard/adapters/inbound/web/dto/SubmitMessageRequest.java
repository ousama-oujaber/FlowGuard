package com.flowguard.adapters.inbound.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitMessageRequest(
        @NotBlank(message = "messageType is required")
        @Size(max = 100, message = "messageType cannot exceed 100 characters")
        String messageType,

        @NotBlank(message = "destinationKey is required")
        @Size(max = 100, message = "destinationKey cannot exceed 100 characters")
        String destinationKey,

        @Size(max = 100, message = "sourceSystem cannot exceed 100 characters")
        String sourceSystem,

        @Size(max = 200, message = "externalReference cannot exceed 200 characters")
        String externalReference,

        @NotNull(message = "payload is required")
        Object payload
) {}
