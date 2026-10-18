package com.flowguard.core.application.port.out;

import com.flowguard.core.domain.model.AuditEvent;
import com.flowguard.core.domain.value.MessageId;
import java.time.Instant;
import java.util.List;

public interface AuditRepositoryPort {

    void save(AuditEvent event);

    List<AuditEvent> findByMessageId(MessageId messageId);

    record AuditSearchCriteria(
            MessageId messageId,
            String actorId,
            String action,
            Instant from,
            Instant to,
            int page,
            int size
    ) {
        public AuditSearchCriteria {
            page = Math.max(0, page);
            size = size <= 0 ? 20 : Math.min(size, 100);
        }
    }

    record AuditSearchResult(
            List<AuditEvent> events,
            long totalElements,
            int page,
            int size
    ) {
        public int totalPages() {
            return size == 0 ? 1 : (int) Math.ceil((double) totalElements / size);
        }
    }

    AuditSearchResult search(AuditSearchCriteria criteria);
}

