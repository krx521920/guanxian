package com.guanxian.platform.member.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.web.MemberUpsertRequest;
import com.guanxian.platform.shared.error.*;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileWorkflowServiceTest {
    static final UUID ASSOCIATION=UUID.randomUUID(), ENTERPRISE=UUID.randomUUID();
    JdbcTemplate jdbc; ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    ProfileWorkflowService service; MemberRepository repository; AuditTrail audit; TransactionTemplate tx;
    ActorScope owner=actor("owner","ENTERPRISE_ADMIN",ASSOCIATION,ENTERPRISE);
    ActorScope reviewer=actor("reviewer","ASSOCIATION_ADMIN",ASSOCIATION,null);
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:profile-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE association(id UUID PRIMARY KEY,status VARCHAR(30))");
        jdbc.execute("CREATE TABLE enterprise(id UUID PRIMARY KEY,association_id UUID,status VARCHAR(30),deleted_at TIMESTAMP,version BIGINT,payload TEXT,name VARCHAR(200) DEFAULT '原企业')");
        jdbc.execute("CREATE TABLE enterprise_profile_workflow(enterprise_id UUID PRIMARY KEY,version BIGINT DEFAULT 0,publication_epoch BIGINT DEFAULT 0,published BOOLEAN DEFAULT FALSE,public_name VARCHAR(200) DEFAULT '',public_category VARCHAR(100) DEFAULT '',draft_status VARCHAR(20),state_json TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE profile_audit(action VARCHAR(100),data TEXT)");
        jdbc.update("INSERT INTO association VALUES(?,'ACTIVE')",ASSOCIATION);
        MemberProfile initial=new MemberProfile(ENTERPRISE,ASSOCIATION,"原企业","CREDIT","原类别","秘密地址","秘密联系人","秘密电话","secret@example.test","原简介",
                List.of("能力"),List.of("产品"),List.of("服务"),List.of("场景"),List.of("内部需求"),"PUBLIC","ACTIVE",0,Instant.now(),Instant.now(),null,null,null);
        jdbc.update("INSERT INTO enterprise(id,association_id,status,deleted_at,version,payload) VALUES(?,?,'ACTIVE',NULL,0,?)",ENTERPRISE,ASSOCIATION,json.writeValueAsString(initial));
        repository=new MemberRepository() {
            public List<MemberProfile> findAll(){return List.of(findById(ENTERPRISE).orElseThrow());}
            public UUID defaultAssociationId(){return ASSOCIATION;}
            public void insert(MemberProfile p){throw new UnsupportedOperationException();}
            public Optional<MemberProfile> findById(UUID id) {
                return jdbc.query("SELECT payload FROM enterprise WHERE id=?",(rs,n)-> {
                    try {return json.readValue(rs.getString(1),MemberProfile.class);}catch(Exception e){throw new IllegalStateException(e);}
                },id).stream().findFirst();
            }
            public boolean update(MemberProfile p,long version) {
                try {return jdbc.update("UPDATE enterprise SET payload=?,version=?,status=? WHERE id=? AND version=?",json.writeValueAsString(p),p.version(),p.status(),p.id(),version)==1;}
                catch(Exception e){throw new IllegalStateException(e);}
            }
        };
        audit=mock(AuditTrail.class);
        doAnswer(call->{jdbc.update("INSERT INTO profile_audit VALUES(?,?)",call.getArgument(1),json.writeValueAsString(call.getArgument(6)));return null;})
                .when(audit).record(any(),anyString(),anyString(),anyString(),any(),any(),any());
        service=new ProfileWorkflowService(jdbc,json,repository,audit);
    }
    static ActorScope actor(String subject,String role,UUID assoc,UUID enterprise) {
        return new ActorScope(UUID.randomUUID(),subject,subject,assoc,enterprise,Set.of(role),Set.of());
    }
    <T>T run(Supplier<T> work){return tx.execute(status->work.get());}
    MemberUpsertRequest content(String introduction) {
        return new MemberUpsertRequest("原企业","CREDIT","新类别","秘密地址","秘密联系人","秘密电话","secret@example.test",introduction,
                List.of("新能力"),List.of("新产品"),List.of("新服务"),List.of("新场景"),List.of("内部需求"),"PUBLIC","DISABLED",ASSOCIATION);
    }
    ProfileWorkflow.View save(String introduction) {
        var v=service.get(ENTERPRISE,owner);
        return run(()->service.save(ENTERPRISE,v.version(),v.official().version(),content(introduction),owner));
    }
    ProfileWorkflow.View submitted() {var d=save("已审核简介");return run(()->service.submit(ENTERPRISE,d.version(),owner));}
    ProfileWorkflow.View approved() {var d=submitted();return run(()->service.review(ENTERPRISE,d.version(),true,"材料已核对",reviewer));}
    ProfileWorkflow.View published() {var a=approved();var c=run(()->service.consent(ENTERPRISE,a.version(),owner));return run(()->service.publish(ENTERPRISE,c.version(),reviewer));}

    @Test void draftAndSubmissionDoNotChangeOfficialOrPublicOrOperationalState() {
        var d=submitted();
        assertEquals("SUBMITTED",d.draft().status()); assertEquals("原简介",d.official().introduction());
        assertEquals(0,d.official().version()); assertEquals("ACTIVE",d.official().status());
        assertTrue(service.publicPage("",0).isEmpty());
        assertThrows(NotFoundException.class,()->service.publicDetail(ENTERPRISE));
        assertThrows(ConflictException.class,()->save("偷改冻结稿"));
        assertEquals(1,service.pending(reviewer,0).size());
        assertTrue(service.pending(actor("foreign","ASSOCIATION_ADMIN",UUID.randomUUID(),null),0).isEmpty());
        assertThrows(ForbiddenException.class,()->service.pending(owner,0));
    }
    @Test void rejectionReasonSurvivesEditingAndResubmission() {
        var d=submitted();var r=run(()->service.review(ENTERPRISE,d.version(),false,"请补充能力证明",reviewer));
        assertEquals("REJECTED",r.draft().status()); assertEquals("原简介",r.official().introduction());
        var s=save("补充证明");assertEquals("请补充能力证明",s.draft().reviewNote());
        var next=run(()->service.submit(ENTERPRISE,s.version(),owner));
        assertEquals("请补充能力证明",next.draft().reviewNote());
        var a=run(()->service.review(ENTERPRISE,next.version(),true,"已核对补充材料",reviewer));
        assertEquals("补充证明",a.official().introduction()); assertEquals("ACTIVE",a.official().status());
        assertEquals(1,a.official().version());assertTrue(service.publicPage("",0).isEmpty());
    }
    @Test void explicitVersionConsentAndIndependentPublicationAreRequired() {
        var a=approved();assertFalse(a.published());
        assertThrows(ConflictException.class,()->run(()->service.publish(ENTERPRISE,a.version(),reviewer)));
        assertThrows(ForbiddenException.class,()->run(()->service.consent(ENTERPRISE,a.version(),reviewer)));
        var c=run(()->service.consent(ENTERPRISE,a.version(),owner));
        assertThrows(ForbiddenException.class,()->run(()->service.publish(ENTERPRISE,c.version(),owner)));
        var p=run(()->service.publish(ENTERPRISE,c.version(),reviewer));assertTrue(p.published());
        assertEquals("已审核简介",service.publicDetail(ENTERPRISE).introduction());
    }
    @Test void publicSnapshotDoesNotChangeDuringNextRevisionOrInternalApproval() throws Exception {
        var published=published();var d=save("不应提前公开的新简介");
        assertEquals("已审核简介",service.publicDetail(ENTERPRISE).introduction());
        var s=run(()->service.submit(ENTERPRISE,d.version(),owner));
        var a=run(()->service.review(ENTERPRISE,s.version(),true,"核对新版",reviewer));
        assertEquals("不应提前公开的新简介",a.official().introduction());
        assertEquals(published.publication(),service.publicDetail(ENTERPRISE));
        assertFalse(a.canPublish()); assertNull(a.approved().consentedAt());
        String publicJson=json.writeValueAsString(service.publicDetail(ENTERPRISE));
        for(String denied:List.of("contact","address","Credit","cooperationNeeds","associationId","review","秘密","内部需求","updatedAt","version"))
            assertFalse(publicJson.contains(denied),publicJson);
    }
    @Test void selfReviewIncludesEveryEditorAndSubmitterIncludingMixedRoles() {
        var helper=actor("helper","ASSOCIATION_ADMIN",ASSOCIATION,null);
        run(()->service.save(ENTERPRISE,0,0,content("代填"),helper));
        var saved=save("企业补充");var s=run(()->service.submit(ENTERPRISE,saved.version(),owner));
        assertThrows(ForbiddenException.class,()->run(()->service.review(ENTERPRISE,s.version(),true,"自审",helper)));
        var mixed=actor("owner","SYSTEM_ADMIN",ASSOCIATION,null);
        assertThrows(ForbiddenException.class,()->run(()->service.review(ENTERPRISE,s.version(),true,"混合自审",mixed)));
    }
    @Test void staffOperatorForeignAndUnscopedSystemCannotReviewOrSeePrivateDrafts() {
        var s=submitted();
        for(ActorScope denied:List.of(actor("staff","ENTERPRISE_MEMBER",ASSOCIATION,ENTERPRISE),actor("foreign","ENTERPRISE_ADMIN",ASSOCIATION,UUID.randomUUID()),
                actor("foreign-admin","ASSOCIATION_ADMIN",UUID.randomUUID(),null),actor("sys","SYSTEM_ADMIN",null,null)))
            assertThrows(ForbiddenException.class,()->service.get(ENTERPRISE,denied));
        var operator=actor("operator","ASSOCIATION_OPERATOR",ASSOCIATION,null);
        assertThrows(ForbiddenException.class,()->run(()->service.review(ENTERPRISE,s.version(),true,"代审",operator)));
    }
    @Test void emptyReviewReasonAndStaleWorkflowAreRejected() {
        var s=submitted();
        assertThrows(ConflictException.class,()->run(()->service.review(ENTERPRISE,s.version(),false," ",reviewer)));
        assertThrows(PreconditionFailedException.class,()->run(()->service.review(ENTERPRISE,0,true,"ok",reviewer)));
    }
    @Test void draftEndpointCannotChangeLegalIdentityOrAssociation() {
        for(var change:Map.of("name","另一法人主体","unifiedSocialCreditCode","OTHER-CREDIT","associationId",UUID.randomUUID().toString()).entrySet()) {
            var node=json.valueToTree(content("普通简介"));
            ((com.fasterxml.jackson.databind.node.ObjectNode)node).put(change.getKey(),change.getValue());
            var tampered=json.convertValue(node,MemberUpsertRequest.class);
            assertThrows(ForbiddenException.class,()->run(()->service.save(ENTERPRISE,0,0,tampered,owner)));
        }
        assertNull(service.get(ENTERPRISE,owner).draft());
        assertEquals(0,repository.findById(ENTERPRISE).orElseThrow().version());
    }
    @Test void concurrentOfficialChangeBlocksApprovalAndStaleDraftSave() {
        var s=submitted();var p=repository.findById(ENTERPRISE).orElseThrow();
        repository.update(MemberService.fromRequest(ENTERPRISE,ASSOCIATION,content("另外核验"),"PUBLIC","ACTIVE",1,p.createdAt(),Instant.now()),0);
        assertThrows(PreconditionFailedException.class,()->run(()->service.review(ENTERPRISE,s.version(),true,"ok",reviewer)));
        var r=run(()->service.review(ENTERPRISE,s.version(),false,"请重新对比正式资料",reviewer));
        assertThrows(PreconditionFailedException.class,()->run(()->service.save(ENTERPRISE,r.version(),0,content("旧客户端"),owner)));
    }
    @Test void withdrawalImmediatelyHidesAndRequiresNewConsent() {
        var p=published();var w=run(()->service.withdraw(ENTERPRISE,p.version(),"负责人撤回",owner));
        assertFalse(w.published());assertNull(w.approved().consentedAt());
        assertThrows(NotFoundException.class,()->service.publicDetail(ENTERPRISE));
        assertTrue(service.publicPage("",0).isEmpty());
        assertThrows(ConflictException.class,()->run(()->service.publish(ENTERPRISE,w.version(),reviewer)));
    }
    @Test void publicQueriesGateLiveLifecycleAndOnlySearchPublishedFields() {
        published();assertTrue(service.publicPage("秘密电话",0).isEmpty());assertEquals(1,service.publicPage("原企业",0).size());
        jdbc.update("UPDATE association SET status='DISABLED' WHERE id=?",ASSOCIATION);
        assertTrue(service.publicPage("",0).isEmpty());assertThrows(NotFoundException.class,()->service.publicDetail(ENTERPRISE));
    }
    @Test void revokedEpochPreventsReuseOfOldConsentEvenAfterOrganizationRecovery() {
        var p=published();jdbc.update("UPDATE enterprise_profile_workflow SET publication_epoch=publication_epoch+1,published=FALSE,version=version+1");
        assertThrows(PreconditionFailedException.class,()->run(()->service.publish(ENTERPRISE,p.version(),reviewer)));
        assertThrows(ConflictException.class,()->run(()->service.publish(ENTERPRISE,p.version()+1,reviewer)));
    }
    @Test void auditFailureRollsBackOfficialAndWorkflowTogether() {
        var s=submitted();doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any(),anyString(),anyString(),anyString(),any(),any(),any());
        assertThrows(IllegalStateException.class,()->run(()->service.review(ENTERPRISE,s.version(),true,"ok",reviewer)));
        assertEquals("原简介",repository.findById(ENTERPRISE).orElseThrow().introduction());
        assertEquals("SUBMITTED",service.get(ENTERPRISE,owner).draft().status());
    }
    @Test void twoReviewersCannotApproveSameWorkflowVersion() throws Exception {
        var s=submitted();var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> work=()->{barrier.await();try{run(()->service.review(ENTERPRISE,s.version(),true,"ok",reviewer));return true;}catch(PreconditionFailedException e){return false;}};
            var a=executor.submit(work);var b=executor.submit(work);
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,repository.findById(ENTERPRISE).orElseThrow().version());
    }
}
