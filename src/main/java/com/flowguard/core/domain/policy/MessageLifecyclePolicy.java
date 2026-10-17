package com.flowguard.core.domain.policy;

import com.flowguard.core.domain.exception.InvalidStateTransitionException;
import com.flowguard.core.domain.model.MessageStatus;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class MessageLifecyclePolicy {

    private static final Map<MessageStatus, Set<MessageStatus>> ALLOWED_TRANSITIONS = Map.of(
            MessageStatus.QUEUED, EnumSet.of(MessageStatus.PROCESSING, MessageStatus.CANCELLED),
            MessageStatus.PROCESSING, EnumSet.of(MessageStatus.SUCCEEDED, MessageStatus.RETRY_SCHEDULED, MessageStatus.DEAD_LETTERED, MessageStatus.RECONCILIATION_REQUIRED),
            MessageStatus.RETRY_SCHEDULED, EnumSet.of(MessageStatus.PROCESSING, MessageStatus.DEAD_LETTERED, MessageStatus.CANCELLED),
            MessageStatus.DEAD_LETTERED, EnumSet.of(MessageStatus.QUEUED),
            MessageStatus.RECONCILIATION_REQUIRED, EnumSet.of(MessageStatus.QUEUED),
            MessageStatus.SUCCEEDED, EnumSet.noneOf(MessageStatus.class),
            MessageStatus.CANCELLED, EnumSet.noneOf(MessageStatus.class)
    );

    private MessageLifecyclePolicy() {
    }

    public static boolean isTransitionAllowed(MessageStatus current, MessageStatus next) {
        if (current == null || next == null) {
            return false;
        }
        Set<MessageStatus> allowed = ALLOWED_TRANSITIONS.get(current);
        return allowed != null && allowed.contains(next);
    }

    public static void validateTransition(MessageStatus current, MessageStatus next) {
        if (!isTransitionAllowed(current, next)) {
            throw new InvalidStateTransitionException(
                    "Illegal state transition: Cannot transition IntegrationMessage from [" + current + "] to [" + next + "]"
            );
        }
    }
}
