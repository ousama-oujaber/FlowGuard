package com.flowguard.core.application.port.in;

public interface ProcessMessagesUseCase {

    /**
     * Claims and processes ready QUEUED and RETRY_SCHEDULED messages.
     *
     * @param batchSize maximum number of messages to claim in one batch
     * @param workerId  unique identifier of the executing worker node/thread
     * @return count of messages claimed and processed in this run
     */
    int processDueMessages(int batchSize, String workerId);
}
