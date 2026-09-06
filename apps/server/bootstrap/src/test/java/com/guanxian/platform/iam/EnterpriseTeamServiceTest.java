package com.guanxian.platform.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.error.*;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static com.guanxian.platform.iam.EnterpriseInvitationServiceTest.*;
import static com.guanxian.platform.iam.EnterpriseInvitations.*;
import static org.junit.jupiter.api.Assertions.*;

class EnterpriseTeamServiceTest {
    EnterpriseInvitationServiceTest f;
    EnterpriseTeamService team;
    ActorScope owner;
    @BeforeEach void setup() throws Exception {
        f=new EnterpriseInvitationServiceTest(); f.setup();
        var account=f.approve(f.claim(f.issue()));
        owner=new ActorScope(account.accountId(),"owner-subject","owner.user",ASSOCIATION,ENTERPRISE,Set.of("ENTERPRISE_ADMIN"),Set.of());
        team=new EnterpriseTeamService(f.named,new ObjectMapper());
    }
    JwtAuthenticationToken member(String name) {return new JwtAuthenticationToken(jwt(name+"-subject",name),List.of(),name);}
    Issued issue(String name) {return f.tx(()->f.service.createMember(name,owner));}
    View claim(Issued invite,String name) {return f.tx(()->f.service.claim(new Claim(invite.token(),true),member(name)));}
    View approve(String name) {return f.approve(claim(issue(name),name));}

    @Test void humanReviewGrantsOnlyReadOnlyMemberAndSuspensionInvalidatesItImmediately() {
        var issued=issue("team.user");
        assertEquals("ENTERPRISE_MEMBER",issued.invitation().targetRole());
        assertFalse(f.grants.isMember(member("team.user").getToken()));
        var claimed=claim(issued,"team.user");
        assertThrows(ForbiddenException.class,()->f.tx(()->f.service.review(claimed.id(),claimed.version(),new Review("APPROVE","自己审核"),owner)));
        var approved=f.approve(claimed);
        assertFalse(f.grants.isOwner(member("team.user").getToken()));
        assertTrue(f.grants.isMember(member("team.user").getToken()));
        var security=new SecurityConfig();ReflectionTestUtils.setField(security,"enterpriseOwners",f.grants);
        var roles=security.jwtAuthenticationConverter("preferred_username").convert(member("team.user").getToken()).getAuthorities().stream().map(a->a.getAuthority()).toList();
        assertTrue(roles.contains("ROLE_ENTERPRISE_MEMBER"));
        for(String forbidden:List.of("ROLE_ENTERPRISE_ADMIN","ENTERPRISE_WRITE","MEMBER_REVIEW","ACCESS_BINDING_WRITE"))assertFalse(roles.contains(forbidden));
        var first=team.members(owner,0);assertEquals(1,first.total());
        assertEquals(approved.accountId(),first.items().getFirst().id());assertTrue(first.items().getFirst().canDisable());
        assertNull(f.service.teamInvitations(owner,0).items().getFirst().claimantSubject());
        assertThrows(PreconditionFailedException.class,()->f.tx(()->{team.disable(approved.accountId(),8,"离职",owner);return null;}));
        f.tx(()->{team.disable(approved.accountId(),0,"人员离职",owner);return null;});
        assertFalse(f.grants.isMember(member("team.user").getToken()));
        assertThrows(ForbiddenException.class,()->f.service.identity(member("team.user")));
        assertEquals("INACTIVE",team.members(owner,0).items().getFirst().status());
        assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='ENTERPRISE_TEAM_MEMBER_DISABLE'",Integer.class));
    }
    @Test void cannotInviteExistingOwnersOrOverwriteBindingOrChooseAnotherScope() {
        assertThrows(ConflictException.class,()->issue("owner.user"));
        var issued=issue("collision.user");claim(issued,"collision.user");
        f.jdbc.update("INSERT INTO user_account(id,external_subject,username,association_id,enterprise_id,status) VALUES(?,'collision.user-subject','collision.user',?,?,'ACTIVE')",UUID.randomUUID(),FOREIGN_ASSOCIATION,FOREIGN_ENTERPRISE);
        assertThrows(ConflictException.class,()->f.approve(f.service.teamInvitations(owner,0).items().getFirst()));
        assertEquals(0,team.members(owner,0).total());
        var mixed=new ActorScope(owner.userId(),owner.subject(),owner.username(),ASSOCIATION,ENTERPRISE,Set.of("ENTERPRISE_ADMIN","SYSTEM_ADMIN"),Set.of());
        assertThrows(ForbiddenException.class,()->team.members(mixed,0));
        assertThrows(ForbiddenException.class,()->f.tx(()->f.service.createMember("new.user",admin())));
        assertThrows(ForbiddenException.class,()->f.tx(()->{team.disable(owner.userId(),0,"删除负责人",owner);return null;}));
        var other=new ActorScope(owner.userId(),owner.subject(),owner.username(),FOREIGN_ASSOCIATION,FOREIGN_ENTERPRISE,owner.roles(),Set.of());
        assertThrows(ForbiddenException.class,()->team.members(other,0));
    }
    @Test void teamInvitationNeverAcceptsAnAdministratorIdentityOrRevokesAnOwnerInvitation() {
        var issued=issue("elevated.user");
        var elevated=new JwtAuthenticationToken(jwt("elevated.user-subject","elevated.user"),List.of(new SimpleGrantedAuthority("ROLE_ENTERPRISE_ADMIN")));
        assertThrows(ForbiddenException.class,()->f.tx(()->f.service.claim(new Claim(issued.token(),true),elevated)));
        var ownerInvite=f.tx(()->f.service.create(new Create(ENTERPRISE,"another.owner"),admin()));
        assertThrows(ForbiddenException.class,()->f.tx(()->f.service.revokeMember(ownerInvite.invitation().id(),0,owner)));
        f.tx(()->f.service.revokeMember(issued.invitation().id(),0,owner));
        assertThrows(ApiException.class,()->claim(issued,"elevated.user"));
    }
    @Test void changedIssuerBindingInvalidatesPendingInvitations() {
        var issued=issue("pending.user");var claimed=claim(issued,"pending.user");
        f.jdbc.update("UPDATE user_account SET version=version+1 WHERE id=?",owner.userId());
        assertThrows(ConflictException.class,()->f.approve(claimed));
        assertEquals("CLAIMED",f.jdbc.queryForObject("SELECT status FROM enterprise_owner_invitation WHERE id=?",String.class,issued.invitation().id()));
        assertFalse(f.grants.isMember(member("pending.user").getToken()));
    }
    @Test void onlyCurrentWorkflowMemberCanBeSuspendedAndAuditFailureRollsBack() {
        var approved=approve("active.user");
        f.jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT fail_team_audit CHECK(action <> 'ENTERPRISE_TEAM_MEMBER_DISABLE')");
        assertThrows(RuntimeException.class,()->f.tx(()->{team.disable(approved.accountId(),0,"离职",owner);return null;}));
        assertTrue(f.grants.isMember(member("active.user").getToken()));
        f.jdbc.update("UPDATE user_account SET version=version+1 WHERE id=?",approved.accountId());
        assertFalse(team.members(owner,0).items().getFirst().canDisable());
        assertThrows(ConflictException.class,()->f.tx(()->{team.disable(approved.accountId(),1,"旧绑定",owner);return null;}));
    }
    @Test void listIsPaginatedAndDoesNotExposeTokensEmailOrExternalSubjects() throws Exception {
        for(int i=0;i<21;i++)issue("pending."+i);
        var first=f.service.teamInvitations(owner,0);var last=f.service.teamInvitations(owner,1);
        assertEquals(21,first.total());assertEquals(20,first.items().size());assertEquals(1,last.items().size());
        var json=new ObjectMapper().findAndRegisterModules().writeValueAsString(last);
        assertFalse(json.contains("token"));assertFalse(json.contains("email"));assertFalse(json.contains("owner-subject"));
    }
}
