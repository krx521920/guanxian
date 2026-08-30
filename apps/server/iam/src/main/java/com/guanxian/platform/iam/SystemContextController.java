package com.guanxian.platform.iam;

import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/system-context")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class SystemContextController {
    private final NamedParameterJdbcTemplate jdbc;
    private final ActorScopeResolver actorScopeResolver;

    SystemContextController(NamedParameterJdbcTemplate jdbc, ActorScopeResolver actorScopeResolver) {
        this.jdbc = jdbc;
        this.actorScopeResolver = actorScopeResolver;
    }

    @GetMapping("/associations")
    ApiResponse<List<AssociationOption>> associations(Authentication authentication) {
        actorScopeResolver.resolve(authentication);
        return ApiResponse.ok(jdbc.query("""
                SELECT id, name
                  FROM association
                 WHERE status='ACTIVE'
                 ORDER BY name, id
                """, (rs, row) -> new AssociationOption(
                rs.getObject("id", UUID.class), rs.getString("name"))));
    }

    @GetMapping("/enterprises")
    ApiResponse<List<EnterpriseOption>> enterprises(
            @RequestParam UUID associationId,
            Authentication authentication) {
        actorScopeResolver.resolve(authentication);
        return ApiResponse.ok(jdbc.query("""
                SELECT id, association_id, name
                 FROM enterprise
                 WHERE association_id=:associationId
                   AND status = 'ACTIVE'
                   AND deleted_at IS NULL
                 ORDER BY name, id
                """, new MapSqlParameterSource("associationId", associationId),
                (rs, row) -> new EnterpriseOption(
                        rs.getObject("id", UUID.class),
                        rs.getObject("association_id", UUID.class),
                        rs.getString("name"))));
    }

    record AssociationOption(UUID id, String name) {
    }

    record EnterpriseOption(UUID id, UUID associationId, String name) {
    }
}
