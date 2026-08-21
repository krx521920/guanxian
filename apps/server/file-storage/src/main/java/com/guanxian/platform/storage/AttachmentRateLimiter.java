package com.guanxian.platform.storage;

import com.guanxian.platform.shared.security.ActorScope;

interface AttachmentRateLimiter {
    void check(ActorScope actor, String action);
}
