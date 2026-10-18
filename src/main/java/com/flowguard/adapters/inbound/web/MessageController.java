package com.flowguard.adapters.inbound.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowguard.adapters.inbound.web.dto.MessageDetailsResponse;
import com.flowguard.adapters.inbound.web.dto.SubmitMessageRequest;
import com.flowguard.adapters.inbound.web.dto.SubmitMessageResponse;
import com.flowguard.core.application.port.in.GetMessageUseCase;
import com.flowguard.core.application.port.in.SubmitMessageUseCase;
import com.flowguard.core.application.port.in.SubmitMessageUseCase.SubmitCommand;
import com.flowguard.core.application.port.in.SubmitMessageUseCase.SubmitResult;
import com.flowguard.core.domain.exception.InvalidPayloadException;
import com.flowguard.core.domain.value.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {

    private final SubmitMessageUseCase submitMessageUseCase;
    private final GetMessageUseCase getMessageUseCase;
    private final ObjectMapper objectMapper;

    public MessageController(
            SubmitMessageUseCase submitMessageUseCase,
            GetMessageUseCase getMessageUseCase,
            ObjectMapper objectMapper
    ) {
        this.submitMessageUseCase = submitMessageUseCase;
        this.getMessageUseCase = getMessageUseCase;
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

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageDetailsResponse> getMessage(
            @PathVariable("id") UUID id,
            @RequestHeader(value = "X-Client-Id", required = false, defaultValue = "client_default") String clientIdHeader
    ) {
        return getMessageUseCase.getMessage(new MessageId(id), new ClientId(clientIdHeader))
                .map(MessageDetailsResponse::fromDomain)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
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
