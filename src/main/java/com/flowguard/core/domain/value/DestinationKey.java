package com.flowguard.core.domain.value;

import com.flowguard.core.domain.exception.InvalidDestinationException;
import java.util.regex.Pattern;

public record DestinationKey(String value) {
    private static final Pattern VALID_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");

    public DestinationKey {
        if (value == null || value.isBlank()) {
            throw new InvalidDestinationException("DestinationKey cannot be blank");
        }
        value = value.trim();
        if (value.contains("://") || value.contains("/") || value.contains(":") || value.contains("?") || value.contains("#")) {
            throw new InvalidDestinationException("DestinationKey cannot be a URL or contain URI scheme/delimiters. Got: " + value);
        }
        if (!VALID_KEY_PATTERN.matcher(value).matches()) {
            throw new InvalidDestinationException("DestinationKey must be alphanumeric, hyphen, or underscore (1-100 chars). Got: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
