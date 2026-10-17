package com.flowguard.core.domain.exception;

public class InvalidStateTransitionException extends DomainException {
    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
