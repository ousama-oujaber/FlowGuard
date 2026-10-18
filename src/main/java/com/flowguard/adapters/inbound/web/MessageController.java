package com.flowguard.adapters.inbound.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowguard.adapters.inbound.web.dto.*;
import com.flowguard.core.application.port.in.*;
import com.flowguard.core.application.port.in.SubmitMessageUseCase.SubmitCommand;
import com.flowguard.core.application.port.in.SubmitMessageUseCase.SubmitResult;
import com.flowguard.core.domain.exception.InvalidPayloadException;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.value.*;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {

    private final SubmitMessageUseCase submitMessageUseCase;
    private final GetMessageUseCase getMessageUseCase;
    private final SearchMessagesUseCase searchMessagesUseCase;
    private final GetAttemptsUseCase getAttemptsUseCase;
    private final ManualRetryUseCase manualRetryUseCase;
    private final ObjectMapper objectMapper;

    public MessageController(
            SubmitMessageUseCase submitMessageUseCase,
            GetMessageUseCase getMessageUseCase,
            SearchMessagesUseCase searchMessagesUseCase,
            GetAttemptsUseCase getAttemptsUseCase,
            ManualRetryUseCase manualRetryUseCase,
            ObjectMapper objectMapper
    ) {
        this.submitMessageUseCase = submitMessageUseCase;
        this.getMessageUseCase = getMessageUseCase;
        this.searchMessagesUseCase = searchMessagesUseCase;
        this.getAttemptsUseCase = getAttemptsUseCase;
        this.manualRetryUseCase = manualRetryUseCase;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SubmitMessageResponse> submitMessage(
            @RequestHeader("Idempotency-Key") String idempotencyKeyHeader,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationIdHeader,
            @RequestHeader(value = "X-Client-Id", required = false, defaultValue = "client_default") String clientIdHeader,
            @Valid @RequestBody SubmitMessageRequest request
    ) {
        String payloadJson = serializePayload(request.payload());

        SubmitCommand command = new SubmitCommand(
                new ClientId(clientIdHeader),
                new MessageType(request.messageType()),
                request.sourceSystem(),
                new DestinationKey(request.destinationKey()),
                correlationIdHeader != null && !correlationIdHeader.isBlank()
                        ? new CorrelationId(correlationIdHeader)
                        : CorrelationId.generate(),
                request.externalReference(),
                payloadJson,
                new IdempotencyKey(idempotencyKeyHeader)
        );

        SubmitResult result = submitMessageUseCase.submit(command);

        SubmitMessageResponse response = SubmitMessageResponse.of(
                result.messageId().value(),
                result.status().name(),
                result.correlationId().value(),
                result.acceptedAt(),
                result.idempotentReplay()
        );

        URI location = URI.create("/api/v1/messages/" + result.messageId().value());

        // Return 200 OK for idempotent replay or 202 Accepted for first-time submission
        HttpStatus status = result.idempotentReplay() ? HttpStatus.OK : HttpStatus.ACCEPTED;

        return ResponseEntity.status(status)
                .location(location)
                .body(response);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageSearchResponse> searchMessages(
            @RequestHeader(value = "X-Client-Id", required = false, defaultValue = "client_default") String clientIdHeader,
            @RequestParam(value = "status", required = false) String statusParam,
            @RequestParam(value = "messageType", required = false) String messageTypeParam,
            @RequestParam(value = "destinationKey", required = false) String destinationKeyParam,
            @RequestParam(value = "correlationId", required = false) String correlationIdParam,
            @RequestParam(value = "createdAfter", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdAfter,
            @RequestParam(value = "createdBefore", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdBefore,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size
    ) {
        MessageStatus status = (statusParam != null && !statusParam.isBlank()) ? MessageStatus.valueOf(statusParam.trim()) : null;
        MessageType messageType = (messageTypeParam != null && !messageTypeParam.isBlank()) ? new MessageType(messageTypeParam.trim()) : null;
        DestinationKey destinationKey = (destinationKeyParam != null && !destinationKeyParam.isBlank()) ? new DestinationKey(destinationKeyParam.trim()) : null;
        CorrelationId correlationId = (correlationIdParam != null && !correlationIdParam.isBlank()) ? new CorrelationId(correlationIdParam.trim()) : null;

        SearchMessagesUseCase.SearchCriteria criteria = new SearchMessagesUseCase.SearchCriteria(
                new ClientId(clientIdHeader),
                status,
                messageType,
                destinationKey,
                correlationId,
                createdAfter,
                createdBefore,
                page,
                size
        );

        SearchMessagesUseCase.SearchResult result = searchMessagesUseCase.search(criteria);

        List<MessageDetailsResponse> itemResponses = result.items().stream()
                .map(MessageDetailsResponse::fromDomain)
                .toList();

        return ResponseEntity.ok(new MessageSearchResponse(
                itemResponses,
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages()
        ));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageDetailsResponse> getMessage(
            @PathVariable("id") UUID id,
            @RequestHeader(value = "X-Client-Id", required = false, defaultValue = "client_default") String clientIdHeader,
            @RequestHeader(value = "X-Scope", required = false, defaultValue = "") String scopeHeader,
            @RequestParam(value = "includePayload", required = false, defaultValue = "false") boolean includePayloadParam
    ) {
        boolean canReadPayload = includePayloadParam || scopeHeader.contains("messages:payload:read");
        return getMessageUseCase.getMessage(new MessageId(id), new ClientId(clientIdHeader))
                .map(msg -> MessageDetailsResponse.fromDomain(msg, canReadPayload, objectMapper))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/{id}/attempts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AttemptDetailsResponse>> getAttempts(
            @PathVariable("id") UUID id,
            @RequestHeader(value = "X-Client-Id", required = false, defaultValue = "client_default") String clientIdHeader
    ) {
        List<com.flowguard.core.domain.model.ProcessingAttempt> attempts =
                getAttemptsUseCase.getAttempts(new MessageId(id), new ClientId(clientIdHeader));

        List<AttemptDetailsResponse> response = attempts.stream()
                .map(AttemptDetailsResponse::fromDomain)
                .toList();

        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/{id}/retries", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ManualRetryResponse> retryMessage(
            @PathVariable("id") UUID id,
            @RequestHeader(value = "X-Actor-Id", required = false, defaultValue = "operator-system") String actorIdHeader,
            @Valid @RequestBody ManualRetryRequest request
    ) {
        ManualRetryUseCase.RetryCommand command =
                new ManualRetryUseCase.RetryCommand(
                        new MessageId(id),
                        actorIdHeader,
                        request.reason()
                );

        ManualRetryUseCase.RetryResult result = manualRetryUseCase.retry(command);

        ManualRetryResponse response = new ManualRetryResponse(
                result.messageId().value(),
                result.status().name(),
                result.auditEventId(),
                result.message()
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    private String serializePayload(Object payload) {
        if (payload == null) {
            throw new InvalidPayloadException("Payload cannot be null");
        }
        if (payload instanceof String str) {
            return str;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new InvalidPayloadException("Failed to serialize message payload: " + e.getMessage());
        }
    }
}
