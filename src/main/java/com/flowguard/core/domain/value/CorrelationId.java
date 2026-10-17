package com.flowguard.core.domain.value;

import com.flowguard.core.domain.exception.DomainException;
import java.util.UUID;

public record CorrelationId(String value) {
    public CorrelationId {
        if (value == null || value.isBlank()) {
            throw new DomainException("CorrelationId cannot be blank");
        }
        value = value.trim();
        if (value.length() > 100) {
            throw new DomainException("CorrelationId cannot exceed 100 characters");
        }
    }

    public static CorrelationId generate() {
        return new CorrelationId("corr-" + UUID.randomUUID().toString().substring(0, 13));
    }

    @Override
    public String toString() {
        return value;
    }
}
