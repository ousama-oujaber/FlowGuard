package com.flowguard.core.application.port.out;

import com.flowguard.core.domain.model.AuditEvent;
import com.flowguard.core.domain.value.MessageId;
import java.util.List;

public interface AuditRepositoryPort {

    void save(AuditEvent event);

    List<AuditEvent> findByMessageId(MessageId messageId);
}
