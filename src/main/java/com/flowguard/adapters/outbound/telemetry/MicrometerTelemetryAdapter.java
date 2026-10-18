package com.flowguard.adapters.outbound.telemetry;

import com.flowguard.core.application.port.out.TelemetryPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Micrometer outbound adapter implementing TelemetryPort to publish Prometheus metrics.
 */
@Component
public class MicrometerTelemetryAdapter implements TelemetryPort {

    private final MeterRegistry registry;
    private final Map<String, Counter> acceptedCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> attemptCounters = new ConcurrentHashMap<>();
    private final Counter cancelledCounter;
    private final Counter deadLetteredCounter;
    private final Counter reconciliationRequiredCounter;

    public MicrometerTelemetryAdapter(MeterRegistry registry) {
        this.registry = registry;
        this.cancelledCounter = Counter.builder("flowguard_cancelled_total")
                .description("Total number of messages explicitly cancelled before dispatch")
                .register(registry);
        this.deadLetteredCounter = Counter.builder("flowguard_dead_lettered_total")
                .description("Total number of messages routed to dead-letter state")
                .register(registry);
        this.reconciliationRequiredCounter = Counter.builder("flowguard_reconciliation_required_total")
                .description("Total number of messages requiring manual reconciliation")
                .register(registry);
    }

    @Override
    public void recordMessageAccepted(String messageType) {
        String type = messageType != null ? messageType : "UNKNOWN";
        acceptedCounters.computeIfAbsent(type, t ->
                Counter.builder("flowguard_messages_accepted_total")
                        .description("Total messages accepted by FlowGuard")
                        .tag("messageType", t)
                        .register(registry)
        ).increment();
    }

    @Override
    public void recordAttemptCompleted(String outcome) {
        String out = outcome != null ? outcome : "UNKNOWN";
        attemptCounters.computeIfAbsent(out, o ->
                Counter.builder("flowguard_processing_attempts_total")
                        .description("Total processing attempts by outcome")
                        .tag("outcome", o)
                        .register(registry)
        ).increment();
    }

    @Override
    public void recordMessageCancelled() {
        cancelledCounter.increment();
    }

    @Override
    public void recordDeadLettered() {
        deadLetteredCounter.increment();
    }

    @Override
    public void recordReconciliationRequired() {
        reconciliationRequiredCounter.increment();
    }
}
