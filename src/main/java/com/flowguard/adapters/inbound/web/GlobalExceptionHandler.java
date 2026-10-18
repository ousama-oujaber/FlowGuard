package com.flowguard.adapters.inbound.web;

import com.flowguard.adapters.inbound.web.dto.ProblemDetailsResponse;
import com.flowguard.core.domain.exception.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MessageNotFoundException.class)
    public ResponseEntity<ProblemDetailsResponse> handleMessageNotFound(
            MessageNotFoundException ex,
            HttpServletRequest request
    ) {
        String correlationId = extractCorrelationId(request);
        log.warn("Message not found for path [{}]: {}", request.getRequestURI(), ex.getMessage());

        ProblemDetailsResponse problem = ProblemDetailsResponse.of(
                "MESSAGE_NOT_FOUND",
                "Message Not Found",
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetailsResponse> handleIdempotencyConflict(
            IdempotencyConflictException ex,
            HttpServletRequest request
    ) {
        String correlationId = extractCorrelationId(request);
        log.warn("Idempotency conflict detected for path [{}]: {}", request.getRequestURI(), ex.getMessage());

        ProblemDetailsResponse problem = ProblemDetailsResponse.of(
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency Key Conflict",
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(InvalidDestinationException.class)
    public ResponseEntity<ProblemDetailsResponse> handleInvalidDestination(
            InvalidDestinationException ex,
            HttpServletRequest request
    ) {
        String correlationId = extractCorrelationId(request);
        log.warn("Invalid destination key rejected for path [{}]: {}", request.getRequestURI(), ex.getMessage());

        ProblemDetailsResponse problem = ProblemDetailsResponse.of(
                "INVALID_DESTINATION",
                "Invalid Destination Key",
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(InvalidPayloadException.class)
    public ResponseEntity<ProblemDetailsResponse> handleInvalidPayload(
            InvalidPayloadException ex,
            HttpServletRequest request
    ) {
        String correlationId = extractCorrelationId(request);
        log.warn("Invalid payload rejected for path [{}]: {}", request.getRequestURI(), ex.getMessage());

        ProblemDetailsResponse problem = ProblemDetailsResponse.of(
                "INVALID_PAYLOAD",
                "Invalid Message Payload",
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ProblemDetailsResponse> handleInvalidStateTransition(
            InvalidStateTransitionException ex,
            HttpServletRequest request
    ) {
        String correlationId = extractCorrelationId(request);
        log.warn("Invalid state transition rejected for path [{}]: {}", request.getRequestURI(), ex.getMessage());

        ProblemDetailsResponse problem = ProblemDetailsResponse.of(
                "INVALID_STATE_TRANSITION",
                "Illegal State Transition",
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetailsResponse> handleMissingHeader(
            MissingRequestHeaderException ex,
            HttpServletRequest request
    ) {
        String correlationId = extractCorrelationId(request);
        String code = "Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())
                ? "MISSING_IDEMPOTENCY_KEY"
                : "MISSING_HEADER";

        ProblemDetailsResponse problem = ProblemDetailsResponse.of(
                code,
                "Missing Required Request Header",
                HttpStatus.BAD_REQUEST.value(),
                "Required header '" + ex.getHeaderName() + "' was not provided.",
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetailsResponse> handleValidationErrors(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        String correlationId = extractCorrelationId(request);
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));

        ProblemDetailsResponse problem = ProblemDetailsResponse.of(
                "VALIDATION_FAILED",
                "Validation Error",
                HttpStatus.BAD_REQUEST.value(),
                details,
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetailsResponse> handleGenericDomainException(
            DomainException ex,
            HttpServletRequest request
    ) {
        String correlationId = extractCorrelationId(request);
        log.warn("Domain rule violation for path [{}]: {}", request.getRequestURI(), ex.getMessage());

        ProblemDetailsResponse problem = ProblemDetailsResponse.of(
                "DOMAIN_RULE_VIOLATION",
                "Domain Rule Violation",
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetailsResponse> handleUnhandledException(
            Exception ex,
            HttpServletRequest request
    ) {
        String correlationId = extractCorrelationId(request);
        log.error("Unhandled internal server error for path [{}] correlation [{}]", request.getRequestURI(), correlationId, ex);

        ProblemDetailsResponse problem = ProblemDetailsResponse.of(
                "INTERNAL_SERVER_ERROR",
                "Internal Server Error",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected internal error occurred. Please contact system support with correlationId: " + correlationId,
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private String extractCorrelationId(HttpServletRequest request) {
        String header = request.getHeader("X-Correlation-ID");
        return (header != null && !header.isBlank()) ? header.trim() : "corr-auto-" + System.currentTimeMillis();
    }
}
