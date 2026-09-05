package com.guanxian.platform.iam;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import java.util.Map;

/** A narrow, current binding grant, not a source of platform administrator roles. */
@Component
@ConditionalOnProperty(name = "guanxian.security.mode", havingValue = "jwt", matchIfMissing = true)
class EnterpriseOwnerAuthorities {
    private final NamedParameterJdbcTemplate jdbc;
    EnterpriseOwnerAuthorities(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    boolean isOwner(Jwt jwt) {
        if (jwt.getSubject() == null || jwt.getSubject().isBlank()) return false;
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM enterprise_owner_grant g
                JOIN user_account u ON u.id=g.account_id
                JOIN enterprise e ON e.id=g.enterprise_id
                JOIN association a ON a.id=g.association_id
                JOIN enterprise_owner_invitation i ON i.id=g.invitation_id
                WHERE g.external_subject=:subject AND u.external_subject=g.external_subject
                  AND u.association_id=g.association_id AND u.enterprise_id=g.enterprise_id
                  AND u.version=g.binding_version AND u.status='ACTIVE'
                  AND e.association_id=g.association_id AND e.deleted_at IS NULL
                  AND e.status NOT IN ('DISABLED', 'DELETED') AND a.status='ACTIVE'
                  AND g.role_code='ENTERPRISE_ADMIN' AND i.status='APPROVED'
                  AND i.account_id=u.id AND i.claim_subject=g.external_subject
                  AND i.enterprise_id=g.enterprise_id AND i.association_id=g.association_id
                  AND NOT EXISTS (SELECT 1 FROM revoked_identity_subject r WHERE r.external_subject=g.external_subject)
                """, Map.of("subject", jwt.getSubject()), Long.class);
        return count != null && count == 1;
    }
}
