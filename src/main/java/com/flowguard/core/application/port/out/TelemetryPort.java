package com.flowguard.core.application.port.out;

/**
 * Output port for operational telemetry, metrics emission, and SLI recording.
 */
public interface TelemetryPort {

    void recordMessageAccepted(String messageType);

    void recordAttemptCompleted(String outcome);

    void recordMessageCancelled();

    void recordDeadLettered();

    void recordReconciliationRequired();
}
