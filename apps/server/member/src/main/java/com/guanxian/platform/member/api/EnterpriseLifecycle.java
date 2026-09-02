package com.guanxian.platform.member.api;

import java.util.UUID;

/**
 * Internal business boundary for deciding whether an enterprise may participate
 * in downstream ecosystem and collaboration workflows.
 *
 * <p>Member profile maintenance intentionally remains outside this boundary so
 * that an incomplete or inactive enterprise can still correct its registration
 * data. Downstream modules must fail closed unless the member is active and has
 * not been soft-deleted.</p>
 */
public interface EnterpriseLifecycle {
    boolean isOperational(UUID enterpriseId);
}
