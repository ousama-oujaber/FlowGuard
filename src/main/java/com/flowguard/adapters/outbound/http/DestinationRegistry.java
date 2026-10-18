package com.flowguard.adapters.outbound.http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of authorized downstream target endpoints and egress policies.
 * Prevents SSRF by only permitting outbound HTTP dispatches to pre-registered logical destination keys.
 */
@Component
@ConfigurationProperties(prefix = "flowguard.egress")
public class DestinationRegistry {

    public record DestinationConfig(
            String targetUrl,
            Duration connectTimeout,
            Duration readTimeout,
            Map<String, String> defaultHeaders
    ) {
        public DestinationConfig {
            if (targetUrl == null || targetUrl.isBlank()) {
                throw new IllegalArgumentException("targetUrl must not be blank");
            }
            URI uri = URI.create(targetUrl);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("targetUrl must use http or https scheme: " + targetUrl);
            }
            if (connectTimeout == null) {
                connectTimeout = Duration.ofSeconds(5);
            }
            if (readTimeout == null) {
                readTimeout = Duration.ofSeconds(10);
            }
            if (defaultHeaders == null) {
                defaultHeaders = Map.of();
            }
        }
    }

    private final Map<String, DestinationConfig> destinations = new ConcurrentHashMap<>();

    public DestinationRegistry() {
        // Built-in registered destination defaults for known enterprise systems
        register("LEDGER_MAIN", new DestinationConfig("https://ledger.internal.corp/api/v1/transactions", Duration.ofSeconds(5), Duration.ofSeconds(10), Map.of()));
        register("HR_WORKDAY", new DestinationConfig("https://workday-connector.internal.corp/payroll", Duration.ofSeconds(5), Duration.ofSeconds(10), Map.of()));
        register("ERP_SAP", new DestinationConfig("https://sap-gateway.internal.corp/odata/v2/Orders", Duration.ofSeconds(5), Duration.ofSeconds(15), Map.of()));
        register("CRM_SALESFORCE", new DestinationConfig("https://crm-sync.internal.corp/events", Duration.ofSeconds(5), Duration.ofSeconds(10), Map.of()));
    }

    public void register(String destinationKey, DestinationConfig config) {
        if (destinationKey == null || destinationKey.isBlank()) {
            throw new IllegalArgumentException("destinationKey must not be blank");
        }
        destinations.put(destinationKey.toUpperCase().trim(), config);
    }

    public Optional<DestinationConfig> get(String destinationKey) {
        if (destinationKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(destinations.get(destinationKey.toUpperCase().trim()));
    }

    public boolean isRegistered(String destinationKey) {
        if (destinationKey == null) {
            return false;
        }
        return destinations.containsKey(destinationKey.toUpperCase().trim());
    }

    public Map<String, DestinationConfig> getDestinations() {
        return destinations;
    }
}
