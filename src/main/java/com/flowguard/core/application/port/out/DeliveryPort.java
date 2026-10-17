package com.flowguard.core.application.port.out;

import com.flowguard.core.domain.model.ErrorCategory;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.value.FailureCode;
import com.flowguard.core.domain.value.SafeErrorSummary;
import java.time.Duration;

public interface DeliveryPort {

    record DeliveryResult(
            boolean success,
            Integer targetStatusCode,
            ErrorCategory errorCategory,
            FailureCode errorCode,
            SafeErrorSummary safeSummary,
            Duration retryAfter
    ) {
        public static DeliveryResult success(int targetStatusCode) {
            return new DeliveryResult(true, targetStatusCode, null, null, null, null);
        }

        public static DeliveryResult failure(
                int targetStatusCode,
                ErrorCategory errorCategory,
                FailureCode errorCode,
                SafeErrorSummary safeSummary,
                Duration retryAfter
        ) {
            return new DeliveryResult(false, targetStatusCode, errorCategory, errorCode, safeSummary, retryAfter);
        }

        public static DeliveryResult ambiguousTimeout(FailureCode errorCode, SafeErrorSummary safeSummary) {
            return new DeliveryResult(false, null, ErrorCategory.REMOTE_TIMEOUT_UNKNOWN, errorCode, safeSummary, null);
        }
    }

    DeliveryResult deliver(IntegrationMessage message);
}
