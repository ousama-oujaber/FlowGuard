package com.flowguard.core.domain.policy;

import com.flowguard.core.domain.model.ErrorCategory;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.value.RetryPolicy;
import java.time.Duration;
import java.time.Instant;

public final class RetryDecisionPolicy {

    private RetryDecisionPolicy() {
    }

    public record Decision(
            MessageStatus nextStatus,
            boolean retryable,
            Instant nextAttemptAt
    ) {}

    public static Decision evaluate(
            int currentAttemptCount,
            int maxAttempts,
            ErrorCategory errorCategory,
            RetryPolicy retryPolicy,
            Duration explicitRetryAfter
    ) {
        // Unknown outcome / timeout on non-idempotent destination -> RECONCILIATION_REQUIRED
        if (errorCategory == ErrorCategory.REMOTE_TIMEOUT_UNKNOWN) {
            return new Decision(MessageStatus.RECONCILIATION_REQUIRED, false, null);
        }

        // Non-retryable error categories -> DEAD_LETTERED
        if (!errorCategory.isDefaultRetryable()) {
            return new Decision(MessageStatus.DEAD_LETTERED, false, null);
        }

        // Check if retry budget is exhausted
        if (currentAttemptCount >= maxAttempts) {
            return new Decision(MessageStatus.DEAD_LETTERED, false, null);
        }

        // Calculate backoff
        Duration delay;
        if (explicitRetryAfter != null && !explicitRetryAfter.isNegative() && !explicitRetryAfter.isZero()) {
            // Respect Retry-After header, but cap at policy maxDelay
            delay = explicitRetryAfter.compareTo(retryPolicy.maxDelay()) > 0
                    ? retryPolicy.maxDelay()
                    : explicitRetryAfter;
        } else {
            delay = retryPolicy.calculateBackoff(currentAttemptCount);
        }

        Instant nextAttemptAt = Instant.now().plus(delay);
        return new Decision(MessageStatus.RETRY_SCHEDULED, true, nextAttemptAt);
    }
}
