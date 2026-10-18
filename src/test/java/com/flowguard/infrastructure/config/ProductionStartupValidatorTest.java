package com.flowguard.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionStartupValidatorTest {

    @Test
    @DisplayName("Non-prod profiles allow empty or default database password")
    void nonProdAllowsDefaults() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("default");
        ProductionStartupValidator validator = new ProductionStartupValidator(env);
        ReflectionTestUtils.setField(validator, "dbPassword", "flowguard");

        assertThatCode(validator::validateProductionConfiguration)
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Prod profile throws IllegalStateException if DB password is default or empty (AC-22)")
    void prodRefusesInsecureDbPassword() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        ProductionStartupValidator validator = new ProductionStartupValidator(env);
        ReflectionTestUtils.setField(validator, "dbPassword", "flowguard");

        assertThatThrownBy(validator::validateProductionConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[AC-22]");
    }

    @Test
    @DisplayName("Prod profile succeeds when secure DB password is provided")
    void prodAcceptsSecurePassword() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        ProductionStartupValidator validator = new ProductionStartupValidator(env);
        ReflectionTestUtils.setField(validator, "dbPassword", "super_secret_production_password_99!");

        assertThatCode(validator::validateProductionConfiguration)
                .doesNotThrowAnyException();
    }
}
