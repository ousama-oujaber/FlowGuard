package com.flowguard.core.domain.value;

import com.flowguard.core.domain.exception.DomainException;

public record SafeErrorSummary(String value) {
    public SafeErrorSummary {
        if (value == null || value.isBlank()) {
            throw new DomainException("SafeErrorSummary cannot be blank");
        }
        value = value.trim();
        if (value.length() > 500) {
            value = value.substring(0, 497) + "...";
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
