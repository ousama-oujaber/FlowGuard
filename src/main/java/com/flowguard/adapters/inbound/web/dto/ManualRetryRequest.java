package com.flowguard.adapters.inbound.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ManualRetryRequest(
        @NotBlank(message = "Audit reason is mandatory for manual retry")
        @Size(max = 1000, message = "Reason cannot exceed 1000 characters")
        String reason
) {}
