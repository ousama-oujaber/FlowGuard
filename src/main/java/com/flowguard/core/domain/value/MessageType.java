package com.flowguard.core.domain.value;

import com.flowguard.core.domain.exception.DomainException;

public record MessageType(String value) {
    public MessageType {
        if (value == null || value.isBlank()) {
            throw new DomainException("MessageType cannot be blank");
        }
        value = value.trim();
        if (value.length() > 100) {
            throw new DomainException("MessageType cannot exceed 100 characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
