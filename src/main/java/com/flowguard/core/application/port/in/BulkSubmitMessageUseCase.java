package com.flowguard.core.application.port.in;

import com.flowguard.core.application.port.in.SubmitMessageUseCase.SubmitCommand;
import com.flowguard.core.application.port.in.SubmitMessageUseCase.SubmitResult;
import com.flowguard.core.domain.value.ClientId;

import java.util.List;

public interface BulkSubmitMessageUseCase {

    int MAX_BATCH_SIZE = 50;

    record BulkSubmitCommand(
            ClientId clientId,
            List<SubmitCommand> items
    ) {}

    record BulkItemOutcome(
            int index,
            String idempotencyKey,
            SubmitResult result
    ) {}

    record BulkSubmitResult(
            int totalCount,
            int acceptedCount,
            int replayedCount,
            List<BulkItemOutcome> items
    ) {}

    BulkSubmitResult submitBatch(BulkSubmitCommand command);
}
