package com.guanxian.platform.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.error.*;
import com.guanxian.platform.shared.security.ActorScope;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Team owners can suspend only workflow-provisioned, current, ordinary enterprise members. */
@Service
@ConditionalOnProperty(name="guanxian.security.mode", havingValue="jwt", matchIfMissing=true)
class EnterpriseTeamService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final String FROM = """
            FROM enterprise_owner_grant g
            JOIN user_account u ON u.id=g.account_id
            JOIN enterprise_owner_invitation i ON i.id=g.invitation_id
            WHERE g.enterprise_id=:enterpriseId AND g.association_id=:associationId
              AND u.enterprise_id=g.enterprise_id AND u.association_id=g.association_id
              AND u.external_subject=g.external_subject AND g.role_code='ENTERPRISE_MEMBER'
              AND i.target_role=g.role_code AND i.status='APPROVED' AND i.account_id=u.id
              AND i.enterprise_id=g.enterprise_id AND i.association_id=g.association_id
              AND i.claim_subject=g.external_subject
            """;
    record Member(UUID id, String username, String displayName, String role, String status, long version, boolean canDisable) { }
    record Page(List<Member> items, long total, int page, int size) { }

    EnterpriseTeamService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    Page members(ActorScope actor, int page) {
        EnterpriseInvitationService.requireTeamOwner(actor);
        requireActiveScope(actor, false);
        int safePage=Math.max(0,Math.min(page,100000));
        var p=params(actor).addValue("offset",(long)safePage*20);
        var items=jdbc.query("SELECT u.id,u.username,u.display_name,u.status,u.version,g.binding_version " + FROM
                + " ORDER BY u.display_name,u.id LIMIT 20 OFFSET :offset",p,(rs,row)->new Member(
                rs.getObject("id",UUID.class),rs.getString("username"),rs.getString("display_name"),"ENTERPRISE_MEMBER",
                "ACTIVE".equals(rs.getString("status")) && rs.getLong("version")!=rs.getLong("binding_version") ? "NEEDS_REVIEW" : rs.getString("status"),
                rs.getLong("version"),"ACTIVE".equals(rs.getString("status")) && rs.getLong("version")==rs.getLong("binding_version")
                    && !actor.userId().equals(rs.getObject("id",UUID.class))));
        Long total=jdbc.queryForObject("SELECT COUNT(*) " + FROM,p,Long.class);
        return new Page(items,Objects.requireNonNullElse(total,0L),safePage,20);
    }

    @Transactional
    void disable(UUID id,long version,String note,ActorScope actor) {
        EnterpriseInvitationService.requireTeamOwner(actor);
        if (actor.userId().equals(id)) throw denied();
        if (note==null || note.isBlank() || note.length()>1000) throw new PreconditionFailedException("请填写停用原因（不超过 1000 字）");
        requireActiveScope(actor,true);
        var p=params(actor).addValue("id",id).addValue("version",version);
        var current=jdbc.query("SELECT u.status,u.version,g.binding_version " + FROM + " AND u.id=:id FOR UPDATE",p,
                (rs,row)->new long[]{rs.getLong("version"),rs.getLong("binding_version"),"ACTIVE".equals(rs.getString("status"))?1:0});
        if(current.size()!=1) throw denied();
        var value=current.getFirst();
        if(value[0]!=version) throw new PreconditionFailedException("成员权限已更新，请刷新后重试");
        if(value[2]!=1 || value[0]!=value[1] || version==Long.MAX_VALUE) throw new ConflictException("该成员不能由企业停用，请联系系统管理员");
        int changed=jdbc.update("UPDATE user_account SET status='INACTIVE',version=version+1,updated_at=now() WHERE id=:id AND version=:version AND status='ACTIVE'",p);
        if(changed!=1) throw new PreconditionFailedException("成员权限已更新，请刷新后重试");
        try {
            jdbc.update("""
                    INSERT INTO audit_log(actor_user_id,actor_subject,actor_username,association_id,enterprise_id,
                      action,resource_type,resource_id,resource_version,outcome,details,request_id)
                    VALUES (:actorId,:subject,:username,:associationId,:enterpriseId,'ENTERPRISE_TEAM_MEMBER_DISABLE',
                      'USER_ACCOUNT',:resourceId,:newVersion,'SUCCESS',CAST(:details AS jsonb),:requestId)
                    """,p.addValue("actorId",actor.userId()).addValue("subject",actor.subject()).addValue("username",actor.username())
                    .addValue("resourceId",id.toString()).addValue("newVersion",version+1)
                    .addValue("details",mapper.writeValueAsString(Map.of("reason",note.trim(),"role","ENTERPRISE_MEMBER")))
                    .addValue("requestId",Objects.requireNonNullElse(MDC.get("requestId"),"internal")));
        } catch(JsonProcessingException e) { throw new IllegalStateException("Team audit failed",e); }
    }

    private void requireActiveScope(ActorScope actor,boolean lock) {
        var scope=jdbc.queryForList("""
                SELECT e.id FROM association a JOIN enterprise e ON e.association_id=a.id
                 WHERE a.id=:associationId AND a.status='ACTIVE' AND e.id=:enterpriseId
                   AND e.status='ACTIVE' AND e.deleted_at IS NULL
                """+(lock?" FOR UPDATE":""),params(actor),UUID.class);
        if(scope.size()!=1) throw denied();
        var owner=jdbc.queryForList("""
                SELECT u.id FROM user_account u WHERE u.id=:actorId AND u.external_subject=:subject AND u.status='ACTIVE'
                  AND u.enterprise_id=:enterpriseId AND u.association_id=:associationId
                  AND NOT EXISTS(SELECT 1 FROM revoked_identity_subject WHERE external_subject=:subject)
                """+EnterpriseInvitationService.CURRENT_OWNER_GRANT+(lock?" FOR UPDATE":""),params(actor).addValue("actorId",actor.userId()).addValue("subject",actor.subject()),UUID.class);
        if(owner.size()!=1) throw denied();
    }
    private static MapSqlParameterSource params(ActorScope actor) { return new MapSqlParameterSource("enterpriseId",actor.enterpriseId()).addValue("associationId",actor.associationId()); }
    private static ForbiddenException denied() { return new ForbiddenException("ENTERPRISE_TEAM_FORBIDDEN","只能管理本企业通过邀请开通的普通成员，不能变更负责人或其他企业权限"); }
}
