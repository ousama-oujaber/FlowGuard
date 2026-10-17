package com.flowguard.core.domain.model;

/**
 * Normalized failure taxonomy.
 */
public enum ErrorCategory {
    VALIDATION(false),
    AUTH(false),
    TRANSIENT_NETWORK(true),
    REMOTE_RATE_LIMIT(true),
    PERMANENT_BUSINESS(false),
    REMOTE_TIMEOUT_UNKNOWN(false),
    CONFIGURATION_FAULT(false),
    INTERNAL_UNEXPECTED(false);

    private final boolean defaultRetryable;

    ErrorCategory(boolean defaultRetryable) {
        this.defaultRetryable = defaultRetryable;
    }

    public boolean isDefaultRetryable() {
        return defaultRetryable;
    }
}
