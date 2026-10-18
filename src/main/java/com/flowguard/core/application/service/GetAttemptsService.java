package com.flowguard.core.application.service;

import com.flowguard.core.application.port.in.GetAttemptsUseCase;
import com.flowguard.core.application.port.out.AttemptRepositoryPort;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.domain.exception.DomainException;
import com.flowguard.core.domain.exception.MessageNotFoundException;
import com.flowguard.core.domain.model.ProcessingAttempt;
import com.flowguard.core.domain.value.ClientId;
import com.flowguard.core.domain.value.MessageId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class GetAttemptsService implements GetAttemptsUseCase {

    private final MessageRepositoryPort messageRepository;
    private final AttemptRepositoryPort attemptRepository;

    public GetAttemptsService(MessageRepositoryPort messageRepository, AttemptRepositoryPort attemptRepository) {
        this.messageRepository = messageRepository;
        this.attemptRepository = attemptRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcessingAttempt> getAttempts(MessageId messageId, ClientId callerClientId) {
        if (messageId == null) {
            throw new DomainException("MessageId cannot be null");
        }
        if (callerClientId == null) {
            throw new DomainException("ClientId cannot be null");
        }

        // Object-level authorization check: Verify message belongs to authorized client scope
        messageRepository.findByIdAndClientId(messageId, callerClientId)
                .orElseThrow(() -> new MessageNotFoundException("Message [" + messageId.value() + "] not found or access denied for client [" + callerClientId.value() + "]"));

        return attemptRepository.findByMessageId(messageId);
    }
}
