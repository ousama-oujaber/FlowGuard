package com.flowguard.core.domain.value;

import com.flowguard.core.domain.exception.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record PayloadFingerprint(String value) {
    public PayloadFingerprint {
        if (value == null || value.isBlank()) {
            throw new DomainException("PayloadFingerprint cannot be blank");
        }
        value = value.trim().toLowerCase();
        if (value.length() != 64) {
            throw new DomainException("PayloadFingerprint must be a 64-character hex SHA-256 digest");
        }
    }

    public static PayloadFingerprint compute(ClientId clientId, MessageType messageType, DestinationKey destinationKey, String normalizedPayload) {
        String canonicalData = clientId.value() + "|" + messageType.value() + "|" + destinationKey.value() + "|" + (normalizedPayload != null ? normalizedPayload.trim() : "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalData.getBytes(StandardCharsets.UTF_8));
            return new PayloadFingerprint(HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
