package com.flowguard.adapters.inbound.web;

import com.flowguard.adapters.inbound.web.dto.OperationsSummaryResponse;
import com.flowguard.core.application.port.in.GetOperationsSummaryUseCase;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {

    private final GetOperationsSummaryUseCase getOperationsSummaryUseCase;

    public OperationsController(GetOperationsSummaryUseCase getOperationsSummaryUseCase) {
        this.getOperationsSummaryUseCase = getOperationsSummaryUseCase;
    }

    @GetMapping(value = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OperationsSummaryResponse> getOperationsSummary(
            @RequestHeader(value = "X-Scope", required = false, defaultValue = "operations:read") String scopeHeader
    ) {
        var summary = getOperationsSummaryUseCase.getSummary();
        return ResponseEntity.ok(OperationsSummaryResponse.fromDomain(summary));
    }
}
