package com.flowguard.core.application.service;

import com.flowguard.core.application.port.in.PurgeExpiredRecordsUseCase;
import com.flowguard.core.application.port.out.IdempotencyRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class PurgeExpiredRecordsService implements PurgeExpiredRecordsUseCase {

    private static final Logger log = LoggerFactory.getLogger(PurgeExpiredRecordsService.class);

    private final IdempotencyRepositoryPort idempotencyRepository;

    public PurgeExpiredRecordsService(IdempotencyRepositoryPort idempotencyRepository) {
        this.idempotencyRepository = idempotencyRepository;
    }

    @Override
    @Transactional
    public int purgeExpiredRecords(Instant cutoff) {
        Instant effectiveCutoff = cutoff != null ? cutoff : Instant.now();
        int deleted = idempotencyRepository.deleteExpiredBefore(effectiveCutoff);
        if (deleted > 0) {
            log.info("Purged [{}] expired idempotency record(s) older than [{}]", deleted, effectiveCutoff);
        }
        return deleted;
    }
}
