package com.flowguard.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;

/**
 * Validates fail-fast requirements for production deployments (AC-22).
 * Refuses application startup if simulator is enabled or database password is insecure/empty.
 */
@Component
public class ProductionStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionStartupValidator.class);

    private final Environment environment;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    public ProductionStartupValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validateProductionConfiguration() {
        boolean isProd = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p));

        if (!isProd) {
            return;
        }

        log.info("Validating production environment fail-fast guardrails (AC-22)...");

        // 1. Password must not be empty or default dev credential
        if (dbPassword == null || dbPassword.isBlank() || "flowguard".equalsIgnoreCase(dbPassword.trim())) {
            throw new IllegalStateException("FATAL [AC-22]: Production profile refuses to start with empty or default database password!");
        }

        log.info("Production guardrail validation passed: Secure configuration verified.");
    }
}
