package com.flowguard.core.application.port.in;

import com.flowguard.core.domain.model.ProcessingAttempt;
import com.flowguard.core.domain.value.ClientId;
import com.flowguard.core.domain.value.MessageId;

import java.util.List;

public interface GetAttemptsUseCase {

    List<ProcessingAttempt> getAttempts(MessageId messageId, ClientId callerClientId);
}
