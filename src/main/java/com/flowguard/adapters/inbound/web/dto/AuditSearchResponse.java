package com.flowguard.adapters.inbound.web.dto;

import com.flowguard.core.application.port.out.AuditRepositoryPort.AuditSearchResult;

import java.util.List;

public record AuditSearchResponse(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<AuditEventResponse> content
) {
    public static AuditSearchResponse fromSearchResult(AuditSearchResult result) {
        List<AuditEventResponse> content = result.events().stream()
                .map(AuditEventResponse::fromDomain)
                .toList();

        return new AuditSearchResponse(
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages(),
                content
        );
    }
}
