package com.flowguard.core.domain.value;

import com.flowguard.core.domain.exception.DomainException;

public record ClientId(String value) {
    public ClientId {
        if (value == null || value.isBlank()) {
            throw new DomainException("ClientId cannot be blank");
        }
        value = value.trim();
        if (value.length() > 100) {
            throw new DomainException("ClientId cannot exceed 100 characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
