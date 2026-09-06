package com.guanxian.platform.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.error.*;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.MDC;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
@ConditionalOnProperty(name="guanxian.security.mode",havingValue="jwt",matchIfMissing=true)
class ManagedEnterpriseAccounts {
    private final NamedParameterJdbcTemplate jdbc;
    private final EnterpriseIdentityProvider provider;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final SecureRandom random=new SecureRandom();
    record View(UUID enterpriseId,String enterpriseName,long enterpriseVersion,boolean enabled,boolean existingBinding,
                String username,String status,long version) { }
    record Result(View account,String temporaryPassword) {
        @Override public String toString() { return "AccountResult[account="+account+", temporaryPassword=[REDACTED]]"; }
    }
    private record Enterprise(UUID id,UUID associationId,String name,long version) { }
    private record Row(UUID id,UUID enterpriseId,UUID associationId,String username,String subject,UUID accountId,
                       Long bindingVersion,String status,long version) { }

    ManagedEnterpriseAccounts(NamedParameterJdbcTemplate jdbc,EnterpriseIdentityProvider provider,ObjectMapper mapper,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.provider=provider;this.mapper=mapper;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    View get(UUID enterpriseId,ActorScope actor) { return tx.execute(s->view(scope(enterpriseId,actor),row(enterpriseId,false))); }

    Result create(UUID enterpriseId,long enterpriseVersion,String requestedUsername,String note,ActorScope actor) {
        available(); String username=normalizeUsername(requestedUsername); String reason=reason(note);
        tx.executeWithoutResult(s->{
            Enterprise enterprise=scope(enterpriseId,actor);
            if(enterprise.version()!=enterpriseVersion) throw stale();
            if(row(enterpriseId,true)!=null || bindings(enterpriseId)>0) throw conflict("企业已有账号或开户任务，请刷新后管理原账号");
            if(count("SELECT count(*) FROM user_account WHERE lower(username)=:username",p("username",username))>0
                    || count("SELECT count(*) FROM enterprise_managed_account WHERE username=:username",p("username",username))>0)
                throw conflict("账号名已被使用，不能覆盖已有账号");
            if(username.equalsIgnoreCase(actor.username())) throw denied();
            UUID operation=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO enterprise_managed_account(id,enterprise_id,association_id,username,status,created_by_subject)
                    VALUES (:id,:enterpriseId,:associationId,:username,'CREATING',:actor)
                    """,p("id",operation).addValue("enterpriseId",enterpriseId).addValue("associationId",enterprise.associationId())
                    .addValue("username",username).addValue("actor",actor.subject()));
            audit(actor,row(enterpriseId,false),"ENTERPRISE_ACCOUNT_CREATE_REQUESTED",reason);
        });
        return finish(enterpriseId,0,actor);
    }
    Result reset(UUID enterpriseId,long version,String note,ActorScope actor) {
        available();String reason=reason(note);
        long pending=tx.execute(s->{
            scope(enterpriseId,actor); Row row=required(enterpriseId,version);
            if(!"ACTIVE".equals(row.status())) throw conflict("账号有未完成操作，请刷新后恢复处理");
            requireBinding(row,actor);
            jdbc.update("UPDATE enterprise_managed_account SET status='RESETTING',version=version+1,tokens_valid_after=:cutoff,updated_at=now() WHERE id=:id",
                    p("id",row.id()).addValue("cutoff",Instant.now().getEpochSecond()));
            audit(actor,row,"ENTERPRISE_ACCOUNT_RESET_REQUESTED",reason);
            return row.version()+1;
        });
        return finish(enterpriseId,pending,actor);
    }
    Result resume(UUID enterpriseId,long version,String note,ActorScope actor) {
        available();String reason=reason(note);
        tx.executeWithoutResult(s->{scope(enterpriseId,actor); Row row=required(enterpriseId,version);
            if("ACTIVE".equals(row.status())) throw conflict("开户已完成。密码不会再次展示，如遗失请明确执行重置");
            audit(actor,row,"ENTERPRISE_ACCOUNT_RESUME_REQUESTED",reason);
        });
        return finish(enterpriseId,version,actor);
    }

    private Result finish(UUID enterpriseId,long version,ActorScope actor) {
        // Persist the external subject before activation. A lost HTTP response never permits adoption
        // of an unrelated username; the provider must verify this operation's protected marker.
        long next=tx.execute(s->{
            scope(enterpriseId,actor);Row row=required(enterpriseId,version);
            if("ACTIVE".equals(row.status())) throw stale();
            if("CREATING".equals(row.status()) && row.subject()==null) {
                if(bindings(enterpriseId)>0) throw conflict("企业绑定已变化，请人工核查未完成开户");
                String subject=provider.ensureDisabledUser(row.username(),row.id());
                jdbc.update("UPDATE enterprise_managed_account SET external_subject=:subject,version=version+1,updated_at=now() WHERE id=:id",
                        p("id",row.id()).addValue("subject",subject));
                return row.version()+1;
            }
            return row.version();
        });
        return tx.execute(s->{
            Enterprise enterprise=scope(enterpriseId,actor);Row row=required(enterpriseId,next);
            if(!Set.of("CREATING","RESETTING").contains(row.status()) || row.subject()==null) throw stale();
            if("RESETTING".equals(row.status())) requireBinding(row,actor);
            else if(bindings(enterpriseId)>0 || count("SELECT count(*) FROM user_account WHERE external_subject=:subject OR lower(username)=:username",
                    p("subject",row.subject()).addValue("username",row.username()))>0) throw conflict("绑定已变化，不能覆盖既有账号");
            provider.verifyUser(row.subject(),row.username(),row.id());
            provider.setEnabled(row.subject(),false);
            provider.logout(row.subject());
            String password=password();
            provider.temporaryPassword(row.subject(),password);
            UUID accountId=row.accountId(); Long bindingVersion=row.bindingVersion();
            if("CREATING".equals(row.status())) {
                accountId=UUID.randomUUID();bindingVersion=0L;
                jdbc.update("""
                        INSERT INTO user_account(id,association_id,enterprise_id,external_subject,username,display_name,status,version,created_at,updated_at)
                        VALUES (:id,:associationId,:enterpriseId,:subject,:username,:name,'ACTIVE',0,now(),now())
                        """,p("id",accountId).addValue("associationId",enterprise.associationId()).addValue("enterpriseId",enterpriseId)
                        .addValue("subject",row.subject()).addValue("username",row.username()).addValue("name",row.username()));
            }
            // If activation succeeds but the DB transaction fails, CREATING/RESETTING remains durable:
            // the bearer converter refuses this identity until an explicit recovery completes.
            provider.setEnabled(row.subject(),true);
            jdbc.update("""
                    UPDATE enterprise_managed_account SET account_id=:accountId,binding_version=:bindingVersion,
                      status='ACTIVE',version=version+1,updated_at=now() WHERE id=:id
                    """,p("id",row.id()).addValue("accountId",accountId).addValue("bindingVersion",bindingVersion));
            Row completed=row(enterpriseId,false);
            audit(actor,completed,"CREATING".equals(row.status())?"ENTERPRISE_ACCOUNT_CREATED":"ENTERPRISE_ACCOUNT_PASSWORD_RESET","管理员已核验接收人；下次登录必须修改临时密码");
            return new Result(view(enterprise,completed),password);
        });
    }
    private Enterprise scope(UUID enterpriseId,ActorScope actor) {
        if(actor==null || !actor.isSystemAdmin() || actor.associationId()==null || actor.subject()==null
                || actor.enterpriseId()!=null && !actor.enterpriseId().equals(enterpriseId)) throw denied();
        if(count("SELECT count(*) FROM revoked_identity_subject WHERE external_subject=:subject",p("subject",actor.subject()))>0) throw denied();
        if(jdbc.queryForList("SELECT id FROM association WHERE id=:id AND status='ACTIVE' FOR UPDATE",p("id",actor.associationId()),UUID.class).size()!=1) throw denied();
        var values=jdbc.query("SELECT id,association_id,name,version FROM enterprise WHERE id=:id AND association_id=:associationId AND status NOT IN ('DISABLED','DELETED') AND deleted_at IS NULL FOR UPDATE",
                p("id",enterpriseId).addValue("associationId",actor.associationId()),(rs,n)->new Enterprise(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getLong(4)));
        if(values.size()!=1) throw denied();
        if(actor.userId()!=null && jdbc.queryForList("SELECT id FROM user_account WHERE id=:id AND external_subject=:subject AND status='ACTIVE' FOR UPDATE",
                p("id",actor.userId()).addValue("subject",actor.subject()),UUID.class).size()!=1) throw denied();
        return values.getFirst();
    }
    private View view(Enterprise e,Row row) {
        String status=row==null?"NOT_CREATED":row.status();
        if(row!=null && row.accountId()!=null && !bindingMatches(row)) status="BINDING_CHANGED";
        return new View(e.id(),e.name(),e.version(),provider.enabled(),bindings(e.id())>0,
                row==null?"ent_"+e.id().toString().replace("-",""):row.username(),status,row==null?0:row.version());
    }
    private boolean bindingMatches(Row row) {
        return count("""
                SELECT count(*) FROM user_account WHERE id=:id AND external_subject=:subject AND username=:username
                  AND enterprise_id=:enterpriseId AND association_id=:associationId AND version=:version AND status='ACTIVE'
                  AND NOT EXISTS(SELECT 1 FROM revoked_identity_subject WHERE external_subject=:subject)
                """,p("id",row.accountId()).addValue("subject",row.subject()).addValue("username",row.username())
                .addValue("enterpriseId",row.enterpriseId()).addValue("associationId",row.associationId()).addValue("version",row.bindingVersion()))==1;
    }
    private void requireBinding(Row row,ActorScope actor) {
        if(Objects.equals(actor.subject(),row.subject()) || Objects.equals(actor.userId(),row.accountId())) throw denied();
        jdbc.queryForList("SELECT id FROM user_account WHERE id=:id FOR UPDATE",p("id",row.accountId()),UUID.class);
        if(!bindingMatches(row)) throw conflict("账号已停用、撤销或归属发生变化，请走身份核验流程；重置密码不会恢复权限");
    }
    private Row required(UUID enterpriseId,long version) {
        Row row=row(enterpriseId,true);if(row==null || row.version()!=version || version>=Long.MAX_VALUE-2) throw stale();return row;
    }
    private Row row(UUID id,boolean lock) {
        return jdbc.query("SELECT * FROM enterprise_managed_account WHERE enterprise_id=:id"+(lock?" FOR UPDATE":""),p("id",id),(rs,n)->
                new Row(rs.getObject("id",UUID.class),rs.getObject("enterprise_id",UUID.class),rs.getObject("association_id",UUID.class),
                        rs.getString("username"),rs.getString("external_subject"),rs.getObject("account_id",UUID.class),
                        rs.getObject("binding_version",Long.class),rs.getString("status"),rs.getLong("version"))).stream().findFirst().orElse(null);
    }
    private long bindings(UUID id) { return count("SELECT count(*) FROM user_account WHERE enterprise_id=:id",p("id",id)); }
    private long count(String sql,MapSqlParameterSource p) { return Objects.requireNonNullElse(jdbc.queryForObject(sql,p,Long.class),0L); }
    private void audit(ActorScope actor,Row row,String action,String note) {
        try {
            jdbc.update("""
                    INSERT INTO audit_log(actor_user_id,actor_subject,actor_username,association_id,enterprise_id,action,
                      resource_type,resource_id,resource_version,outcome,details,request_id)
                    VALUES (:actorId,:subject,:username,:associationId,:enterpriseId,:action,'ENTERPRISE_ACCOUNT',:resourceId,:version,'SUCCESS',CAST(:details AS jsonb),:requestId)
                    """,p("actorId",actor.userId()).addValue("subject",actor.subject()).addValue("username",actor.username())
                    .addValue("associationId",row.associationId()).addValue("enterpriseId",row.enterpriseId()).addValue("action",action)
                    .addValue("resourceId",row.id().toString()).addValue("version",row.version())
                    .addValue("details",mapper.writeValueAsString(Map.of("note",note,"targetUsername",row.username(),"role","ENTERPRISE_ADMIN")))
                    .addValue("requestId",Objects.requireNonNullElse(MDC.get("requestId"),"internal")));
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Account audit failed"); }
    }
    private String password() {
        byte[] bytes=new byte[24];random.nextBytes(bytes);
        return "Gx!9aA"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private void available() { if(!provider.enabled()) throw new ApiException("ACCOUNT_PROVISIONING_DISABLED","管理员开户尚未配置，请联系运维人员",HttpStatus.SERVICE_UNAVAILABLE); }
    static String normalizeUsername(String input) {
        String value=input==null?"":input.strip().toLowerCase(Locale.ROOT);
        if(!value.matches("[a-z][a-z0-9._-]{2,63}")) throw conflict("账号名须为 3–64 位小写字母、数字、点、下划线或短横线，以字母开头");
        return value;
    }
    private static String reason(String value) { if(value==null || value.isBlank() || value.length()>1000) throw conflict("请填写接收人核验依据或重置原因（不超过 1000 字，不填写密码）");return value.strip(); }
    private static MapSqlParameterSource p(String name,Object value) { return new MapSqlParameterSource(name,value); }
    private static ConflictException conflict(String text) { return new ConflictException(text); }
    private static ForbiddenException denied() { return new ForbiddenException("ENTERPRISE_ACCOUNT_FORBIDDEN","仅系统管理员可以在明确的协会范围内开通企业账号"); }
    private static PreconditionFailedException stale() { return new PreconditionFailedException("账号或企业资料已变化，请刷新后重试"); }
}
