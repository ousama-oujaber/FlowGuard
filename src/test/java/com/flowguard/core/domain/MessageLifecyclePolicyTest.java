package com.flowguard.core.domain;

import com.flowguard.core.domain.exception.InvalidStateTransitionException;
import com.flowguard.core.domain.model.MessageStatus;
import com.flowguard.core.domain.policy.MessageLifecyclePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageLifecyclePolicyTest {

    @Test
    @DisplayName("Allowed transitions succeed according to specification")
    void allowedTransitions() {
        assertThat(MessageLifecyclePolicy.isTransitionAllowed(MessageStatus.QUEUED, MessageStatus.PROCESSING)).isTrue();
        assertThat(MessageLifecyclePolicy.isTransitionAllowed(MessageStatus.PROCESSING, MessageStatus.SUCCEEDED)).isTrue();
        assertThat(MessageLifecyclePolicy.isTransitionAllowed(MessageStatus.PROCESSING, MessageStatus.RETRY_SCHEDULED)).isTrue();
        assertThat(MessageLifecyclePolicy.isTransitionAllowed(MessageStatus.PROCESSING, MessageStatus.DEAD_LETTERED)).isTrue();
        assertThat(MessageLifecyclePolicy.isTransitionAllowed(MessageStatus.PROCESSING, MessageStatus.RECONCILIATION_REQUIRED)).isTrue();
        assertThat(MessageLifecyclePolicy.isTransitionAllowed(MessageStatus.RETRY_SCHEDULED, MessageStatus.PROCESSING)).isTrue();
        assertThat(MessageLifecyclePolicy.isTransitionAllowed(MessageStatus.DEAD_LETTERED, MessageStatus.QUEUED)).isTrue();
        assertThat(MessageLifecyclePolicy.isTransitionAllowed(MessageStatus.RECONCILIATION_REQUIRED, MessageStatus.QUEUED)).isTrue();
    }

    @Test
    @DisplayName("Forbidden transitions throw InvalidStateTransitionException")
    void forbiddenTransitions() {
        // Cannot jump directly from QUEUED to SUCCEEDED
        assertThatThrownBy(() -> MessageLifecyclePolicy.validateTransition(MessageStatus.QUEUED, MessageStatus.SUCCEEDED))
                .isInstanceOf(InvalidStateTransitionException.class);

        // Terminal SUCCEEDED cannot be reprocessed or transitioned
        assertThatThrownBy(() -> MessageLifecyclePolicy.validateTransition(MessageStatus.SUCCEEDED, MessageStatus.PROCESSING))
                .isInstanceOf(InvalidStateTransitionException.class);

        assertThatThrownBy(() -> MessageLifecyclePolicy.validateTransition(MessageStatus.SUCCEEDED, MessageStatus.QUEUED))
                .isInstanceOf(InvalidStateTransitionException.class);

        // Terminal CANCELLED cannot be transitioned
        assertThatThrownBy(() -> MessageLifecyclePolicy.validateTransition(MessageStatus.CANCELLED, MessageStatus.QUEUED))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
