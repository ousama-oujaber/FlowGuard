package com.flowguard.core.application.port.out;

import com.flowguard.core.domain.model.ProcessingAttempt;
import com.flowguard.core.domain.value.MessageId;
import java.util.List;

public interface AttemptRepositoryPort {

    void save(ProcessingAttempt attempt);

    List<ProcessingAttempt> findByMessageId(MessageId messageId);
}
