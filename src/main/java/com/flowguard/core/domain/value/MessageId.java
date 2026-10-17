package com.flowguard.core.domain.value;

import com.flowguard.core.domain.exception.DomainException;
import java.util.Objects;
import java.util.UUID;

public record MessageId(UUID value) {
    public MessageId {
        if (value == null) {
            throw new DomainException("MessageId cannot be null");
        }
    }

    public static MessageId generate() {
        return new MessageId(UUID.randomUUID());
    }

    public static MessageId fromString(String id) {
        if (id == null || id.isBlank()) {
            throw new DomainException("MessageId string cannot be blank");
        }
        try {
            return new MessageId(UUID.fromString(id.trim()));
        } catch (IllegalArgumentException e) {
            throw new DomainException("Invalid UUID format for MessageId: " + id, e);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
