package com.flowguard.adapters.inbound.web.dto;

import java.time.Instant;

/**
 * Standard error representation following RFC 9457 Problem Details for HTTP APIs.
 */
public record ProblemDetailsResponse(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        String correlationId,
        Instant timestamp
) {
    public static ProblemDetailsResponse of(String code, String title, int status, String detail, String instance, String correlationId) {
        String typeUri = "https://flowguard.io/errors/" + code.toLowerCase().replace('_', '-');
        return new ProblemDetailsResponse(
                typeUri,
                title,
                status,
                detail,
                instance,
                code,
                correlationId,
                Instant.now()
        );
    }
}
