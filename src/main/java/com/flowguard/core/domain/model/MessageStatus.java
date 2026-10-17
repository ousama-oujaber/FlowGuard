package com.flowguard.core.domain.model;

/**
 * Lifecycle states of an Integration Message.
 */
public enum MessageStatus {
    /**
     * Message durably persisted and awaiting worker claim.
     */
    QUEUED,

    /**
     * Message claimed by an active worker under a valid lease.
     */
    PROCESSING,

    /**
     * Message successfully delivered to destination. Terminal state.
     */
    SUCCEEDED,

    /**
     * Transient failure encountered; backoff delay scheduled before next attempt.
     */
    RETRY_SCHEDULED,

    /**
     * Permanent failure encountered or retry budget exhausted. Requires operator investigation.
     */
    DEAD_LETTERED,

    /**
     * Ambiguous timeout/outcome on non-idempotent destination.
     * Automatic retry is forbidden to prevent duplicate business side effects.
     */
    RECONCILIATION_REQUIRED,

    /**
     * Message explicitly cancelled prior to execution. Terminal state.
     */
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == CANCELLED;
    }

    public boolean requiresOperatorAction() {
        return this == DEAD_LETTERED || this == RECONCILIATION_REQUIRED;
    }
}
