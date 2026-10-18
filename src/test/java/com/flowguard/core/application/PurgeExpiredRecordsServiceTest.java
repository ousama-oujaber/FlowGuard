package com.flowguard.core.application;

import com.flowguard.core.application.port.out.IdempotencyRepositoryPort;
import com.flowguard.core.application.service.PurgeExpiredRecordsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurgeExpiredRecordsServiceTest {

    @Mock
    private IdempotencyRepositoryPort idempotencyRepository;

    @InjectMocks
    private PurgeExpiredRecordsService service;

    @Test
    @DisplayName("Purge delegates to repository with cutoff and returns count of deleted records")
    void purgesExpiredRecords() {
        Instant cutoff = Instant.now();
        when(idempotencyRepository.deleteExpiredBefore(cutoff)).thenReturn(42);

        int count = service.purgeExpiredRecords(cutoff);

        assertThat(count).isEqualTo(42);
        verify(idempotencyRepository).deleteExpiredBefore(cutoff);
    }

    @Test
    @DisplayName("Null cutoff defaults to current timestamp")
    void nullCutoffDefaultsToNow() {
        when(idempotencyRepository.deleteExpiredBefore(any(Instant.class))).thenReturn(5);

        int count = service.purgeExpiredRecords(null);

        assertThat(count).isEqualTo(5);
        verify(idempotencyRepository).deleteExpiredBefore(any(Instant.class));
    }
}
