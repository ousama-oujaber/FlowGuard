package com.flowguard.core.application.port.in;

import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.value.MessageId;
import java.util.UUID;

public interface ManualRetryUseCase {

    record RetryCommand(
            MessageId messageId,
            String actorId,
            String reason
    ) {}

    record RetryResult(
            MessageId messageId,
            MessageStatus status,
            UUID auditEventId,
            String message
    ) {}

    RetryResult retry(RetryCommand command);
}
