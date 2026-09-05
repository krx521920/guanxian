package com.guanxian.platform.member.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static com.guanxian.platform.member.internal.ProfileWorkflowServiceTest.actor;

@Testcontainers(disabledWithoutDocker=true)
class ProfileWorkflowPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void fullMigrationsPreservePrivateDraftAndInvalidatePublicationAcrossDisableAndRestore() {
        var ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        UUID assoc=UUID.randomUUID(),id=UUID.randomUUID();
        jdbc.update("INSERT INTO association(id,name,status) VALUES(?,'资料发布测试协会','ACTIVE')",assoc);
        jdbc.update("INSERT INTO enterprise(id,association_id,name,category,status,visibility) VALUES(?,?,'隔离企业','原类别','ACTIVE','PUBLIC')",id,assoc);
        var json=new ObjectMapper().findAndRegisterModules();
        var repo=new PostgresMemberRepository(new NamedParameterJdbcTemplate(ds),json,"资料发布测试协会");
        var service=new ProfileWorkflowService(jdbc,json,repo,mock(AuditTrail.class));
        var owner=actor("owner","ENTERPRISE_ADMIN",assoc,id);var reviewer=actor("reviewer","ASSOCIATION_ADMIN",assoc,null);
        var body=new com.guanxian.platform.member.web.MemberUpsertRequest("隔离企业",null,"新类别","内部地址","内部人","内部电话","公开简介",java.util.List.of(),java.util.List.of(),java.util.List.of(),"ACTIVE");
        var d=tx.execute(s->service.save(id,0,0,body,owner));
        assertEquals("原类别",repo.findById(id).orElseThrow().category());
        var submitted=tx.execute(s->service.submit(id,d.version(),owner));
        var approved=tx.execute(s->service.review(id,submitted.version(),true,"核对",reviewer));
        assertTrue(service.publicPage("",0).isEmpty());
        var consented=tx.execute(s->service.consent(id,approved.version(),owner));
        tx.execute(s->service.publish(id,consented.version(),reviewer));assertEquals(1,service.publicPage("",0).size());
        jdbc.update("UPDATE association SET status='DISABLED' WHERE id=?",assoc);
        jdbc.update("UPDATE association SET status='ACTIVE' WHERE id=?",assoc);
        assertTrue(service.publicPage("",0).isEmpty());
        var after=service.get(id,reviewer);assertFalse(after.canPublish());
        var renewed=tx.execute(s->service.consent(id,after.version(),owner));
        tx.execute(s->service.publish(id,renewed.version(),reviewer));
        jdbc.update("UPDATE enterprise SET status='DISABLED',version=version+1 WHERE id=?",id);
        jdbc.update("UPDATE enterprise SET status='ACTIVE',version=version+1 WHERE id=?",id);
        assertTrue(service.publicPage("",0).isEmpty());
        assertFalse(service.get(id,reviewer).canPublish());
    }
}
