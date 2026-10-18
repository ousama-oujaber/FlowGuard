package com.flowguard.adapters.outbound.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerTelemetryAdapterTest {

    private SimpleMeterRegistry registry;
    private MicrometerTelemetryAdapter adapter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        adapter = new MicrometerTelemetryAdapter(registry);
    }

    @Test
    @DisplayName("Increments flowguard_messages_accepted_total counter with messageType tag")
    void recordsMessageAccepted() {
        adapter.recordMessageAccepted("ORDER_PAYMENT_V1");

        Counter counter = registry.find("flowguard_messages_accepted_total")
                .tag("messageType", "ORDER_PAYMENT_V1")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Increments flowguard_processing_attempts_total counter with outcome tag")
    void recordsAttemptCompleted() {
        adapter.recordAttemptCompleted("SUCCEEDED");

        Counter counter = registry.find("flowguard_processing_attempts_total")
                .tag("outcome", "SUCCEEDED")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Increments flowguard_cancelled_total counter")
    void recordsMessageCancelled() {
        adapter.recordMessageCancelled();

        Counter counter = registry.find("flowguard_cancelled_total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Increments flowguard_dead_lettered_total and reconciliation_required counters")
    void recordsTerminalCounters() {
        adapter.recordDeadLettered();
        adapter.recordReconciliationRequired();

        assertThat(registry.find("flowguard_dead_lettered_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("flowguard_reconciliation_required_total").counter().count()).isEqualTo(1.0);
    }
}
