package com.guanxian.platform.shared.security;

import org.springframework.security.core.Authentication;

public interface ActorScopeResolver {
    ActorScope resolve(Authentication authentication);
}
