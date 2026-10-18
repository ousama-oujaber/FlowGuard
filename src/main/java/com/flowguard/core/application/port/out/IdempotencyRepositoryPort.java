package com.flowguard.core.application.port.out;

import com.flowguard.core.domain.value.ClientId;
import com.flowguard.core.domain.value.MessageId;
import com.flowguard.core.domain.value.PayloadFingerprint;
import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRepositoryPort {

    record IdempotencyEntry(
            ClientId clientId,
            String keyDigest,
            PayloadFingerprint requestFingerprint,
            MessageId messageId,
            Instant createdAt,
            Instant expiresAt
    ) {}

    boolean save(IdempotencyEntry entry);

    Optional<IdempotencyEntry> find(ClientId clientId, String keyDigest);

    int deleteExpiredBefore(Instant cutoff);
}
