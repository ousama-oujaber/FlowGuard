package com.flowguard.core.domain.exception;

public class EmptyBatchException extends DomainException {
    public EmptyBatchException(String message) {
        super(message);
    }
}
