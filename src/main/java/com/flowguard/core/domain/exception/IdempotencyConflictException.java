package com.flowguard.core.domain.exception;

public class IdempotencyConflictException extends DomainException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
