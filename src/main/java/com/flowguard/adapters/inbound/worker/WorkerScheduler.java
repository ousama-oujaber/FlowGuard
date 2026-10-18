package com.flowguard.adapters.inbound.worker;

import com.flowguard.core.application.port.in.ProcessMessagesUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "flowguard.worker.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkerScheduler.class);

    private final ProcessMessagesUseCase processMessagesUseCase;
    private final int batchSize;
    private final String workerId;

    public WorkerScheduler(
            ProcessMessagesUseCase processMessagesUseCase,
            @Value("${flowguard.worker.batch-size:10}") int batchSize
    ) {
        this.processMessagesUseCase = processMessagesUseCase;
        this.batchSize = batchSize > 0 ? batchSize : 10;
        this.workerId = "worker-" + ManagementFactory.getRuntimeMXBean().getName();
    }

    @Scheduled(fixedDelayString = "${flowguard.worker.poll-interval-ms:1000}")
    public void runWorker() {
        try {
            int processed = processMessagesUseCase.processDueMessages(batchSize, workerId);
            if (processed > 0) {
                log.debug("Worker poll cycle completed: [{}] messages processed.", processed);
            }
        } catch (Exception ex) {
            log.error("Error during worker polling cycle: {}", ex.getMessage(), ex);
        }
    }
}
