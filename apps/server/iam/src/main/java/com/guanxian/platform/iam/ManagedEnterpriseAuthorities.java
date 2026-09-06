package com.guanxian.platform.iam;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@ConditionalOnProperty(name="guanxian.security.mode",havingValue="jwt",matchIfMissing=true)
class ManagedEnterpriseAuthorities {
    private final NamedParameterJdbcTemplate jdbc;
    ManagedEnterpriseAuthorities(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    boolean owner(Jwt jwt) {
        if(jwt.getSubject()==null) return false;
        var rows=jdbc.query("""
                SELECT m.status,m.tokens_valid_after,
                  CASE WHEN u.id=m.account_id AND u.status='ACTIVE' AND u.version=m.binding_version
                    AND u.external_subject=m.external_subject AND u.enterprise_id=m.enterprise_id AND u.association_id=m.association_id
                    AND e.association_id=m.association_id AND e.deleted_at IS NULL AND e.status NOT IN ('DISABLED','DELETED') AND a.status='ACTIVE'
                    AND NOT EXISTS(SELECT 1 FROM revoked_identity_subject r WHERE r.external_subject=m.external_subject)
                    THEN 1 ELSE 0 END AS current_binding
                FROM enterprise_managed_account m LEFT JOIN user_account u ON u.id=m.account_id
                JOIN enterprise e ON e.id=m.enterprise_id JOIN association a ON a.id=m.association_id
                WHERE m.external_subject=:subject
                """,Map.of("subject",jwt.getSubject()),(rs,n)->new Object[]{rs.getString(1),rs.getLong(2),rs.getInt(3)});
        if(rows.isEmpty()) return false;
        Object[] row=rows.getFirst();
        if(rows.size()!=1 || !"ACTIVE".equals(row[0]) || !Integer.valueOf(1).equals(row[2])
                || jwt.getIssuedAt()==null || jwt.getIssuedAt().getEpochSecond()<=(Long)row[1])
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token"),"账号操作尚未完成、授权已变化或会话已失效，请重新登录或联系管理员");
        return true;
    }
}
