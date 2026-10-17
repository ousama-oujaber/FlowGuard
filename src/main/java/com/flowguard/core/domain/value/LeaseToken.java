package com.flowguard.core.domain.value;

import com.flowguard.core.domain.exception.DomainException;
import java.util.UUID;

public record LeaseToken(UUID value) {
    public LeaseToken {
        if (value == null) {
            throw new DomainException("LeaseToken cannot be null");
        }
    }

    public static LeaseToken generate() {
        return new LeaseToken(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
