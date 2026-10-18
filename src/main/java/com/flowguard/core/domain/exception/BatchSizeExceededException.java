package com.flowguard.core.domain.exception;

public class BatchSizeExceededException extends DomainException {
    public BatchSizeExceededException(String message) {
        super(message);
    }
}
