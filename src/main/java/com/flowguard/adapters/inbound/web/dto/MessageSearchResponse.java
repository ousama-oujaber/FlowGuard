package com.flowguard.adapters.inbound.web.dto;

import java.util.List;

public record MessageSearchResponse(
        List<MessageDetailsResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
