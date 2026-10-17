package com.flowguard.core.domain.policy;

import com.flowguard.core.domain.exception.IdempotencyConflictException;
import com.flowguard.core.domain.value.PayloadFingerprint;

public final class IdempotencyPolicy {

    private IdempotencyPolicy() {
    }

    public enum CheckResult {
        IDENTICAL_REPLAY,
        CONFLICT
    }

    public static CheckResult verify(PayloadFingerprint existingFingerprint, PayloadFingerprint incomingFingerprint) {
        if (existingFingerprint.equals(incomingFingerprint)) {
            return CheckResult.IDENTICAL_REPLAY;
        }
        throw new IdempotencyConflictException(
                "Idempotency key reuse conflict: Key was previously submitted with payload fingerprint ["
                        + existingFingerprint + "] but current request has fingerprint [" + incomingFingerprint + "]"
        );
    }
}
