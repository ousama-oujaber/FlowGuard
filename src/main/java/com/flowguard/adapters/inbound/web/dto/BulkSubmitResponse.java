package com.flowguard.adapters.inbound.web.dto;

import java.util.List;

public record BulkSubmitResponse(
        int totalCount,
        int acceptedCount,
        int replayedCount,
        List<BulkSubmitItemResponse> items
) {}
