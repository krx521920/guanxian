package com.guanxian.platform.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import static com.guanxian.platform.iam.EnterpriseInvitations.*;
import static com.guanxian.platform.iam.EnterpriseInvitationServiceTest.*;

@Testcontainers(disabledWithoutDocker=true)
class EnterpriseInvitationPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void fullMigrationAndTransactionalInvitationFlowUseRealPostgresConstraints() {
        var ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        var jdbc=new JdbcTemplate(ds);
        jdbc.update("INSERT INTO association(id,name,status) VALUES(?,'邀请验证协会','ACTIVE')",ASSOCIATION);
        jdbc.update("INSERT INTO enterprise(id,association_id,name,category,status) VALUES(?,?,'邀请验证企业','技术服务','ACTIVE')",ENTERPRISE,ASSOCIATION);
        var named=new NamedParameterJdbcTemplate(ds);
        var service=new EnterpriseInvitationService(named,new ObjectMapper());
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var issued=tx.execute(s->service.create(new Create(ENTERPRISE,"owner.user"),admin()));
        var claimed=tx.execute(s->service.claim(new Claim(issued.token(),true),owner()));
        var approved=tx.execute(s->service.review(claimed.id(),claimed.version(),new Review("APPROVE","隔离测试核验"),admin()));
        assertEquals("APPROVED",approved.status());
        assertTrue(new EnterpriseOwnerAuthorities(named).isOwner(owner().getToken()));
        assertEquals(ENTERPRISE,jdbc.queryForObject("SELECT enterprise_id FROM user_account WHERE id=?",java.util.UUID.class,approved.accountId()));
        jdbc.update("UPDATE user_account SET version=version+1 WHERE id=?",approved.accountId());
        assertFalse(new EnterpriseOwnerAuthorities(named).isOwner(owner().getToken()));
    }
}
