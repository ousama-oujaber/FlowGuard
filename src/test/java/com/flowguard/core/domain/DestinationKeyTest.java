package com.flowguard.core.domain;

import com.flowguard.core.domain.exception.InvalidDestinationException;
import com.flowguard.core.domain.value.DestinationKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DestinationKeyTest {

    @Test
    @DisplayName("Valid alphanumeric destination key with hyphens and underscores succeeds")
    void validDestinationKey() {
        DestinationKey key = new DestinationKey("DEMO_LEDGER_01");
        assertThat(key.value()).isEqualTo("DEMO_LEDGER_01");
        assertThat(key.toString()).isEqualTo("DEMO_LEDGER_01");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://evil.com/webhook",
            "https://169.254.169.254/metadata",
            "ftp://files.example.com",
            "internal.server:8080",
            "ledger/v1/post",
            "ledger?param=val",
            "ledger#fragment"
    })
    @DisplayName("Raw URLs and URIs are strictly rejected to prevent SSRF vulnerabilities")
    void rejectsRawUrlsAndUriDelimiters(String rawUrl) {
        assertThatThrownBy(() -> new DestinationKey(rawUrl))
                .isInstanceOf(InvalidDestinationException.class)
                .hasMessageContaining("cannot be a URL or contain URI scheme/delimiters");
    }

    @Test
    @DisplayName("Blank destination key is rejected")
    void rejectsBlank() {
        assertThatThrownBy(() -> new DestinationKey("   "))
                .isInstanceOf(InvalidDestinationException.class);
    }
}
