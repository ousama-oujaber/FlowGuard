package com.flowguard.core.application.service;

import com.flowguard.core.application.port.in.SearchAuditEventsUseCase;
import com.flowguard.core.application.port.out.AuditRepositoryPort;
import com.flowguard.core.application.port.out.AuditRepositoryPort.AuditSearchCriteria;
import com.flowguard.core.application.port.out.AuditRepositoryPort.AuditSearchResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchAuditEventsService implements SearchAuditEventsUseCase {

    private final AuditRepositoryPort auditRepository;

    public SearchAuditEventsService(AuditRepositoryPort auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public AuditSearchResult searchAuditEvents(AuditSearchCriteria criteria) {
        if (criteria == null) {
            criteria = new AuditSearchCriteria(null, null, null, null, null, 0, 20);
        }
        return auditRepository.search(criteria);
    }
}
