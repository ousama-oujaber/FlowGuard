package com.flowguard.core.domain.value;

import com.flowguard.core.domain.exception.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record IdempotencyKey(String value) {
    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new DomainException("IdempotencyKey cannot be blank");
        }
        value = value.trim();
        if (value.length() > 128) {
            throw new DomainException("IdempotencyKey cannot exceed 128 characters");
        }
    }

    public String computeDigest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
