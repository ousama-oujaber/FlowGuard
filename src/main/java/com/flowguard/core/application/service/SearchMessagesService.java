package com.flowguard.core.application.service;

import com.flowguard.core.application.port.in.SearchMessagesUseCase;
import com.flowguard.core.application.port.out.MessageRepositoryPort;
import com.flowguard.core.domain.exception.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchMessagesService implements SearchMessagesUseCase {

    private final MessageRepositoryPort messageRepository;

    public SearchMessagesService(MessageRepositoryPort messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public SearchResult search(SearchCriteria criteria) {
        if (criteria == null) {
            throw new DomainException("SearchCriteria cannot be null");
        }
        if (criteria.clientId() == null) {
            throw new DomainException("clientId is mandatory for scoped message search");
        }
        return messageRepository.search(criteria);
    }
}
