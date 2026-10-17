package com.flowguard.core.domain.value;

import com.flowguard.core.domain.exception.DomainException;

public record FailureCode(String value) {
    public FailureCode {
        if (value == null || value.isBlank()) {
            throw new DomainException("FailureCode cannot be blank");
        }
        value = value.trim().toUpperCase();
        if (value.length() > 80) {
            throw new DomainException("FailureCode cannot exceed 80 characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
