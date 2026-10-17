package com.flowguard.core.domain;

import com.flowguard.core.domain.exception.IdempotencyConflictException;
import com.flowguard.core.domain.policy.IdempotencyPolicy;
import com.flowguard.core.domain.value.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyPolicyTest {

    private final ClientId clientId = new ClientId("client_payroll");
    private final MessageType messageType = new MessageType("PAYROLL_V1");
    private final DestinationKey destinationKey = new DestinationKey("LEDGER_MAIN");

    @Test
    @DisplayName("Matching fingerprints indicate identical replay")
    void identicalFingerprintMatches() {
        PayloadFingerprint fp1 = PayloadFingerprint.compute(clientId, messageType, destinationKey, "{\"amount\":100}");
        PayloadFingerprint fp2 = PayloadFingerprint.compute(clientId, messageType, destinationKey, "{\"amount\":100}");

        IdempotencyPolicy.CheckResult result = IdempotencyPolicy.verify(fp1, fp2);
        assertThat(result).isEqualTo(IdempotencyPolicy.CheckResult.IDENTICAL_REPLAY);
    }

    @Test
    @DisplayName("Differing payloads produce different fingerprints and trigger conflict exception")
    void differingPayloadTriggersConflict() {
        PayloadFingerprint original = PayloadFingerprint.compute(clientId, messageType, destinationKey, "{\"amount\":100}");
        PayloadFingerprint altered = PayloadFingerprint.compute(clientId, messageType, destinationKey, "{\"amount\":200}");

        assertThatThrownBy(() -> IdempotencyPolicy.verify(original, altered))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("Idempotency key reuse conflict");
    }
}
