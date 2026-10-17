package com.flowguard.core.domain;

import com.flowguard.core.domain.model.ErrorCategory;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.policy.RetryDecisionPolicy;
import com.flowguard.core.domain.value.RetryPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryDecisionPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(10), 2.0, false);

    @Test
    @DisplayName("Transient network failure with remaining attempts schedules retry with backoff")
    void transientFailureSchedulesRetry() {
        RetryDecisionPolicy.Decision decision = RetryDecisionPolicy.evaluate(
                1,
                3,
                ErrorCategory.TRANSIENT_NETWORK,
                policy,
                null
        );

        assertThat(decision.nextStatus()).isEqualTo(MessageStatus.RETRY_SCHEDULED);
        assertThat(decision.retryable()).isTrue();
        assertThat(decision.nextAttemptAt()).isNotNull();
    }

    @Test
    @DisplayName("Transient failure with exhausted retry budget moves to DEAD_LETTERED")
    void exhaustedRetriesMovesToDeadLetter() {
        RetryDecisionPolicy.Decision decision = RetryDecisionPolicy.evaluate(
                3, // attempt 3 of 3
                3,
                ErrorCategory.TRANSIENT_NETWORK,
                policy,
                null
        );

        assertThat(decision.nextStatus()).isEqualTo(MessageStatus.DEAD_LETTERED);
        assertThat(decision.retryable()).isFalse();
        assertThat(decision.nextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("Permanent business error immediately moves to DEAD_LETTERED")
    void permanentFailureDeadLettersImmediately() {
        RetryDecisionPolicy.Decision decision = RetryDecisionPolicy.evaluate(
                1,
                3,
                ErrorCategory.PERMANENT_BUSINESS,
                policy,
                null
        );

        assertThat(decision.nextStatus()).isEqualTo(MessageStatus.DEAD_LETTERED);
        assertThat(decision.retryable()).isFalse();
    }

    @Test
    @DisplayName("Ambiguous remote timeout moves to RECONCILIATION_REQUIRED and avoids automatic retry")
    void ambiguousTimeoutRequiresReconciliation() {
        RetryDecisionPolicy.Decision decision = RetryDecisionPolicy.evaluate(
                1,
                3,
                ErrorCategory.REMOTE_TIMEOUT_UNKNOWN,
                policy,
                null
        );

        assertThat(decision.nextStatus()).isEqualTo(MessageStatus.RECONCILIATION_REQUIRED);
        assertThat(decision.retryable()).isFalse();
        assertThat(decision.nextAttemptAt()).isNull();
    }
}
