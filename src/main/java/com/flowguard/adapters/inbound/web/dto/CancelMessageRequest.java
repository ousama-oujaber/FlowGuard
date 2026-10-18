package com.flowguard.adapters.inbound.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelMessageRequest(
        @NotBlank(message = "reason must not be blank")
        @Size(max = 500, message = "reason cannot exceed 500 characters")
        String reason
) {}
