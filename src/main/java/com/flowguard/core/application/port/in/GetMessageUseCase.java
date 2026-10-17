package com.flowguard.core.application.port.in;

import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.value.ClientId;
import com.flowguard.core.domain.value.MessageId;
import java.util.Optional;

public interface GetMessageUseCase {

    Optional<IntegrationMessage> getMessage(MessageId id, ClientId callerClientId);
}
