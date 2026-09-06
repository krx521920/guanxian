package com.guanxian.platform.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.error.*;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.guanxian.platform.iam.EnterpriseInvitationServiceTest.*;

class ManagedEnterpriseAccountsTest {
    EnterpriseInvitationServiceTest fixture;
    ManagedEnterpriseAccounts accounts;
    ManagedEnterpriseAuthorities grants;
    FakeIdentityProvider provider;
    @BeforeEach void setup() throws Exception {
        fixture=new EnterpriseInvitationServiceTest();fixture.setup();
        fixture.jdbc.execute("ALTER TABLE enterprise ADD version BIGINT DEFAULT 0 NOT NULL");
        provider=new FakeIdentityProvider();
        accounts=new ManagedEnterpriseAccounts(fixture.named,provider,new ObjectMapper(),new DataSourceTransactionManager(fixture.jdbc.getDataSource()));
        grants=new ManagedEnterpriseAuthorities(fixture.named);
    }
    @Test void createsBoundOwnerAndOneTimePasswordWithoutInvitationsOrRoleEscalation() throws Exception {
        var result=accounts.create(ENTERPRISE,0,"new.owner","已核验企业授权",admin());
        assertEquals("ACTIVE",result.account().status());assertEquals(2,result.account().version());
        assertTrue(result.temporaryPassword().length()>=32);assertTrue(provider.enabled);
        assertEquals(result.temporaryPassword(),provider.password);
        assertFalse(result.toString().contains(result.temporaryPassword()));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT count(*) FROM enterprise_owner_invitation",Integer.class));
        assertEquals(1,fixture.jdbc.queryForObject("SELECT count(*) FROM user_account WHERE enterprise_id=?",Integer.class,ENTERPRISE));
        assertTrue(grants.owner(token(Instant.now().plusSeconds(2))));
        var security=new SecurityConfig();ReflectionTestUtils.setField(security,"managedEnterpriseAccounts",grants);
        var jwt=Jwt.withTokenValue("test").header("alg","RS256").subject(provider.subject).issuedAt(Instant.now())
                .claim("roles",List.of("SYSTEM_ADMIN")).claim("permissions",List.of("ACCESS_BINDING_WRITE")).build();
        var permissions=security.jwtAuthenticationConverter("preferred_username").convert(jwt).getAuthorities().toString();
        assertTrue(permissions.contains("ROLE_ENTERPRISE_ADMIN"));assertFalse(permissions.contains("ROLE_SYSTEM_ADMIN"));assertFalse(permissions.contains("ACCESS_BINDING_WRITE"));
        String serialized=new ObjectMapper().writeValueAsString(accounts.get(ENTERPRISE,admin()));
        assertFalse(serialized.contains(result.temporaryPassword()));assertFalse(serialized.contains("temporaryPassword"));
        assertFalse(fixture.jdbc.queryForList("SELECT details FROM audit_log").toString().contains(result.temporaryPassword()));
        assertThrows(ApiException.class,()->accounts.create(ENTERPRISE,0,"duplicate.owner","核验",admin()));
        assertEquals(1,provider.created);
    }
    @Test void resetRevokesOldTokensAndNeverRestoresChangedOrInactiveBinding() {
        var created=accounts.create(ENTERPRISE,0,"reset.owner","核验",admin());
        Jwt old=token(Instant.now().minusSeconds(5)); assertTrue(grants.owner(old));
        var reset=accounts.reset(ENTERPRISE,created.account().version(),"负责人忘记密码，已核验",admin());
        assertNotEquals(created.temporaryPassword(),reset.temporaryPassword());
        assertThrows(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,()->grants.owner(old));
        assertTrue(grants.owner(token(Instant.now().plusSeconds(2))));
        fixture.jdbc.update("UPDATE user_account SET status='INACTIVE',version=version+1 WHERE external_subject=?",provider.subject);
        assertEquals("BINDING_CHANGED",accounts.get(ENTERPRISE,admin()).status());
        assertThrows(ApiException.class,()->accounts.reset(ENTERPRISE,reset.account().version(),"不能恢复停用账号",admin()));
        assertThrows(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,()->grants.owner(token(Instant.now().plusSeconds(2))));
    }
    @Test void lostCreateResponseResumesOnlyTheReservedIdentityAndNeverDuplicates() {
        provider.failAfterCreate=true;
        assertThrows(ApiException.class,()->accounts.create(ENTERPRISE,0,"pending.owner","核验",admin()));
        assertEquals("CREATING",accounts.get(ENTERPRISE,admin()).status());
        assertFalse(provider.enabled);assertEquals(0,fixture.jdbc.queryForObject("SELECT count(*) FROM user_account",Integer.class));
        provider.failAfterCreate=false;
        var result=accounts.resume(ENTERPRISE,accounts.get(ENTERPRISE,admin()).version(),"恢复本次开户",admin());
        assertEquals("ACTIVE",result.account().status());assertEquals(1,provider.created);
        assertThrows(ApiException.class,()->accounts.resume(ENTERPRISE,result.account().version(),"重复",admin()));
    }
    @Test void auditFailureAfterRemoteActivationCannotUnlockHalfCreatedAccount() {
        fixture.jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT fail_final_audit CHECK(action<>'ENTERPRISE_ACCOUNT_CREATED')");
        assertThrows(RuntimeException.class,()->accounts.create(ENTERPRISE,0,"audit.owner","核验",admin()));
        assertTrue(provider.enabled);
        assertEquals("CREATING",accounts.get(ENTERPRISE,admin()).status());
        assertEquals(0,fixture.jdbc.queryForObject("SELECT count(*) FROM user_account",Integer.class));
        assertThrows(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,()->grants.owner(token(Instant.now())));
        fixture.jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT fail_final_audit");
        assertEquals("ACTIVE",accounts.resume(ENTERPRISE,accounts.get(ENTERPRISE,admin()).version(),"恢复审计故障后的开户",admin()).account().status());
    }
    @Test void resetFailureBlocksOldSessionUntilExplicitRecovery() {
        var created=accounts.create(ENTERPRISE,0,"failure.owner","核验",admin());
        provider.failPassword=true;
        assertThrows(ApiException.class,()->accounts.reset(ENTERPRISE,created.account().version(),"核验后重置",admin()));
        var pending=accounts.get(ENTERPRISE,admin());assertEquals("RESETTING",pending.status());
        assertThrows(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,()->grants.owner(token(Instant.now().plusSeconds(60))));
        provider.failPassword=false;
        assertEquals("ACTIVE",accounts.resume(ENTERPRISE,pending.version(),"恢复已核验重置",admin()).account().status());
        assertThrows(ApiException.class,()->accounts.reset(ENTERPRISE,pending.version(),"过期版本",admin()));
    }
    @Test void refusesWrongRoleScopeUsernameVersionAndExistingBindingsBeforeAnyIdentityMutation() {
        assertThrows(ApiException.class,()->accounts.create(ENTERPRISE,0,"test.owner","核验",actor("assoc",ASSOCIATION,"ASSOCIATION_ADMIN")));
        assertThrows(ApiException.class,()->accounts.create(FOREIGN_ENTERPRISE,0,"test.owner","核验",admin()));
        assertThrows(ApiException.class,()->accounts.create(ENTERPRISE,99,"test.owner","核验",admin()));
        assertThrows(ApiException.class,()->accounts.create(ENTERPRISE,0,"../admin","核验",admin()));
        assertThrows(ApiException.class,()->accounts.create(ENTERPRISE,0,"test.owner","",admin()));
        fixture.jdbc.update("INSERT INTO user_account(id,external_subject,username,enterprise_id,association_id,status) VALUES(?, 'existing','existing',?,?,'ACTIVE')",UUID.randomUUID(),ENTERPRISE,ASSOCIATION);
        assertThrows(ApiException.class,()->accounts.create(ENTERPRISE,0,"test.owner","核验",admin()));assertEquals(0,provider.created);
    }
    @Test void disabledConnectorDoesNotCreateJobsOrPasswords() {
        provider.available=false;
        assertFalse(accounts.get(ENTERPRISE,admin()).enabled());
        assertThrows(ApiException.class,()->accounts.create(ENTERPRISE,0,"test.owner","核验",admin()));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT count(*) FROM enterprise_managed_account",Integer.class));
    }
    @Test void provisionedOwnerCanManageTeamButStaleDirectGrantCannotIssueInvitations() {
        accounts.create(ENTERPRISE,0,"team.owner","核验",admin());
        UUID id=fixture.jdbc.queryForObject("SELECT id FROM user_account",UUID.class);
        var owner=new ActorScope(id,provider.subject,"team.owner",ASSOCIATION,ENTERPRISE,Set.of("ENTERPRISE_ADMIN"),Set.of());
        assertEquals("ENTERPRISE_MEMBER",fixture.tx(()->fixture.service.createMember("team.member",owner)).invitation().targetRole());
        fixture.jdbc.update("UPDATE user_account SET version=version+1 WHERE id=?",id);
        assertThrows(ApiException.class,()->fixture.tx(()->fixture.service.createMember("other.member",owner)));
    }
    private Jwt token(Instant time) { return Jwt.withTokenValue("fixture").header("alg","RS256").subject(provider.subject).issuedAt(time).build(); }
    static class FakeIdentityProvider implements EnterpriseIdentityProvider {
        String subject=UUID.randomUUID().toString(),username,password;UUID operation;
        boolean enabled,available=true,failAfterCreate,failPassword;int created;
        public boolean enabled() { return available; }
        public String ensureDisabledUser(String name,UUID id) {
            if(username==null) { username=name;operation=id;created++; }
            if(!username.equals(name) || !operation.equals(id) || failAfterCreate) throw KeycloakEnterpriseIdentityProvider.failure();
            return subject;
        }
        public void verifyUser(String subject,String username,UUID id) { if(!this.subject.equals(subject)||!this.username.equals(username)||!operation.equals(id)) throw KeycloakEnterpriseIdentityProvider.failure(); }
        public void setEnabled(String subject,boolean enabled) { this.enabled=enabled; }
        public void temporaryPassword(String subject,String password) { if(failPassword) throw KeycloakEnterpriseIdentityProvider.failure(); this.password=password; }
        public void logout(String subject) { }
    }
}
