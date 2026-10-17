package com.flowguard.core.domain.model;

/**
 * Status of an individual execution attempt.
 */
public enum AttemptStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN
}
