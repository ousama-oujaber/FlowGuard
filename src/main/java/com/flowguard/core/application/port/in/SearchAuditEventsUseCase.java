package com.flowguard.core.application.port.in;

import com.flowguard.core.application.port.out.AuditRepositoryPort.AuditSearchCriteria;
import com.flowguard.core.application.port.out.AuditRepositoryPort.AuditSearchResult;

public interface SearchAuditEventsUseCase {

    AuditSearchResult searchAuditEvents(AuditSearchCriteria criteria);
}
