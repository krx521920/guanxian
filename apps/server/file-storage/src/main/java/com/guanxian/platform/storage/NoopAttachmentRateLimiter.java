package com.guanxian.platform.storage;

import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "guanxian.storage.rate-limit.enabled",
        havingValue = "false",
        matchIfMissing = true)
final class NoopAttachmentRateLimiter implements AttachmentRateLimiter {
    @Override
    public void check(ActorScope actor, String action) {
        // Deliberately disabled by configuration for local/test environments.
    }
}
