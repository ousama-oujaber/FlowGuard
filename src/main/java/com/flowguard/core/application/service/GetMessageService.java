package com.flowguard.core.application.service;

import com.flowguard.core.application.port.in.GetMessageUseCase;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.domain.model.IntegrationMessage;
import com.flowguard.core.domain.value.ClientId;
import com.flowguard.core.domain.value.MessageId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class GetMessageService implements GetMessageUseCase {

    private final MessageRepositoryPort messageRepository;

    public GetMessageService(MessageRepositoryPort messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IntegrationMessage> getMessage(MessageId id, ClientId callerClientId) {
        if (id == null || callerClientId == null) {
            return Optional.empty();
        }
        return messageRepository.findByIdAndClientId(id, callerClientId);
    }
}
