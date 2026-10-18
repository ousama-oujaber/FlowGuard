package com.flowguard.adapters.inbound.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record BulkSubmitRequest(
        @NotNull(message = "items cannot be null")
        @NotEmpty(message = "items cannot be empty")
        @Valid
        List<BulkSubmitItemRequest> items
) {}
