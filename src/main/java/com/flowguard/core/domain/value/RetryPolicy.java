package com.flowguard.core.domain.value;

import com.flowguard.core.domain.exception.DomainException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public record RetryPolicy(
        int maxAttempts,
        Duration initialDelay,
        Duration maxDelay,
        double multiplier,
        boolean jitterEnabled
) {
    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new DomainException("maxAttempts must be >= 1");
        }
        if (initialDelay == null || initialDelay.isNegative() || initialDelay.isZero()) {
            throw new DomainException("initialDelay must be positive");
        }
        if (maxDelay == null || maxDelay.compareTo(initialDelay) < 0) {
            throw new DomainException("maxDelay must be >= initialDelay");
        }
        if (multiplier < 1.0) {
            throw new DomainException("multiplier must be >= 1.0");
        }
    }

    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(5, Duration.ofSeconds(5), Duration.ofMinutes(10), 2.0, true);
    }

    public Duration calculateBackoff(int attemptCount) {
        if (attemptCount <= 1) {
            return initialDelay;
        }

        // Exponential delay: initialDelay * multiplier^(attemptCount - 1)
        double exponentialFactor = Math.pow(multiplier, attemptCount - 1);
        long computedMillis = (long) (initialDelay.toMillis() * exponentialFactor);
        long cappedMillis = Math.min(computedMillis, maxDelay.toMillis());

        if (!jitterEnabled) {
            return Duration.ofMillis(cappedMillis);
        }

        // Full jitter: uniform between 50% and 150% of the calculated backoff
        double jitterFactor = ThreadLocalRandom.current().nextDouble(0.5, 1.5);
        long jitteredMillis = (long) (cappedMillis * jitterFactor);
        return Duration.ofMillis(Math.max(initialDelay.toMillis(), Math.min(jitteredMillis, maxDelay.toMillis())));
    }
}
