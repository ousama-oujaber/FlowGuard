package com.flowguard.core.domain.exception;

public class MessageNotFoundException extends DomainException {
    public MessageNotFoundException(String message) {
        super(message);
    }
}
