package com.flowguard.core.application.port.in;

import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.value.*;
import java.time.Instant;

public interface SubmitMessageUseCase {

    record SubmitCommand(
            ClientId clientId,
            MessageType messageType,
            String sourceSystem,
            DestinationKey destinationKey,
            CorrelationId correlationId,
            String externalReference,
            String payload,
            IdempotencyKey idempotencyKey
    ) {}

    record SubmitResult(
            MessageId messageId,
            MessageStatus status,
            CorrelationId correlationId,
            Instant acceptedAt,
            boolean idempotentReplay
    ) {}

    SubmitResult submit(SubmitCommand command);
}
