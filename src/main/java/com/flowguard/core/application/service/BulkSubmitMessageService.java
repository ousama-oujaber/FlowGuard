package com.flowguard.core.application.service;

import com.flowguard.core.application.port.in.BulkSubmitMessageUseCase;
import com.flowguard.core.application.port.in.SubmitMessageUseCase;
import com.flowguard.core.application.port.in.SubmitMessageUseCase.SubmitCommand;
import com.flowguard.core.application.port.in.SubmitMessageUseCase.SubmitResult;
import com.flowguard.core.domain.exception.BatchSizeExceededException;
import com.flowguard.core.domain.exception.DomainException;
import com.flowguard.core.domain.exception.EmptyBatchException;
import com.flowguard.core.domain.policy.IdempotencyPolicy;
import com.flowguard.core.domain.value.PayloadFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class BulkSubmitMessageService implements BulkSubmitMessageUseCase {

    private final SubmitMessageUseCase submitMessageUseCase;

    public BulkSubmitMessageService(SubmitMessageUseCase submitMessageUseCase) {
        this.submitMessageUseCase = submitMessageUseCase;
    }

    @Override
    @Transactional
    public BulkSubmitResult submitBatch(BulkSubmitCommand command) {
        if (command == null || command.items() == null) {
            throw new EmptyBatchException("Batch command and items cannot be null");
        }
        if (command.items().isEmpty()) {
            throw new EmptyBatchException("Batch cannot be empty");
        }
        if (command.items().size() > MAX_BATCH_SIZE) {
            throw new BatchSizeExceededException(
                    "Batch size " + command.items().size() + " exceeds maximum allowed limit of " + MAX_BATCH_SIZE
            );
        }

        // Intra-batch idempotency collision check
        Map<String, PayloadFingerprint> seenKeys = new HashMap<>();
        for (int i = 0; i < command.items().size(); i++) {
            SubmitCommand item = command.items().get(i);
            if (item == null) {
                throw new DomainException("Batch item at index " + i + " cannot be null");
            }
            if (item.idempotencyKey() == null) {
                throw new DomainException("Batch item at index " + i + " requires an IdempotencyKey");
            }
            String keyDigest = item.idempotencyKey().computeDigest();
            PayloadFingerprint fingerprint = PayloadFingerprint.compute(
                    item.clientId(),
                    item.messageType(),
                    item.destinationKey(),
                    item.payload()
            );

            if (seenKeys.containsKey(keyDigest)) {
                PayloadFingerprint previousFingerprint = seenKeys.get(keyDigest);
                IdempotencyPolicy.verify(previousFingerprint, fingerprint);
            } else {
                seenKeys.put(keyDigest, fingerprint);
            }
        }

        // Process each item atomically within the transaction
        List<BulkItemOutcome> outcomes = new ArrayList<>(command.items().size());
        int acceptedCount = 0;
        int replayedCount = 0;

        for (int i = 0; i < command.items().size(); i++) {
            SubmitCommand item = command.items().get(i);
            SubmitResult result = submitMessageUseCase.submit(item);
            if (result.idempotentReplay()) {
                replayedCount++;
            } else {
                acceptedCount++;
            }
            outcomes.add(new BulkItemOutcome(i, item.idempotencyKey().value(), result));
        }

        return new BulkSubmitResult(
                command.items().size(),
                acceptedCount,
                replayedCount,
                outcomes
        );
    }
}
