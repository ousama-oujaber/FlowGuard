package com.flowguard.adapters.inbound.web;

import com.flowguard.adapters.inbound.web.dto.AuditSearchResponse;
import com.flowguard.core.application.port.in.SearchAuditEventsUseCase;
import com.flowguard.core.application.port.out.AuditRepositoryPort.AuditSearchCriteria;
import com.flowguard.core.application.port.out.AuditRepositoryPort.AuditSearchResult;
import com.flowguard.core.domain.value.MessageId;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditEventController {

    private final SearchAuditEventsUseCase searchAuditEventsUseCase;

    public AuditEventController(SearchAuditEventsUseCase searchAuditEventsUseCase) {
        this.searchAuditEventsUseCase = searchAuditEventsUseCase;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuditSearchResponse> searchAuditEvents(
            @RequestHeader(value = "X-Scope", required = false, defaultValue = "audit:read") String scopeHeader,
            @RequestParam(value = "messageId", required = false) UUID messageId,
            @RequestParam(value = "actorId", required = false) String actorId,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size
    ) {
        if (scopeHeader != null && !scopeHeader.isBlank()
                && !scopeHeader.contains("audit:read")
                && !scopeHeader.contains("operations:read")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        AuditSearchCriteria criteria = new AuditSearchCriteria(
                messageId != null ? new MessageId(messageId) : null,
                actorId,
                action,
                from,
                to,
                page,
                size
        );

        AuditSearchResult result = searchAuditEventsUseCase.searchAuditEvents(criteria);
        return ResponseEntity.ok(AuditSearchResponse.fromSearchResult(result));
    }
}
