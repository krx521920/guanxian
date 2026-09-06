package com.guanxian.platform.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.guanxian.platform.iam.EnterpriseInvitationServiceTest.*;

@Testcontainers(disabledWithoutDocker=true)
class ManagedEnterpriseAccountsPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void directProvisioningMigrationGrantRecoveryAndPasswordResetUseRealPostgres() {
        var ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();var jdbc=new JdbcTemplate(ds);
        jdbc.update("INSERT INTO association(id,name,status) VALUES(?,'开户隔离验证','ACTIVE')",ASSOCIATION);
        jdbc.update("INSERT INTO enterprise(id,association_id,name,category,status) VALUES(?,?,'开户隔离企业','技术服务','ACTIVE')",ENTERPRISE,ASSOCIATION);
        var provider=new ManagedEnterpriseAccountsTest.FakeIdentityProvider();var named=new NamedParameterJdbcTemplate(ds);
        var service=new ManagedEnterpriseAccounts(named,provider,new ObjectMapper(),new DataSourceTransactionManager(ds));
        provider.failAfterCreate=true;
        assertThrows(RuntimeException.class,()->service.create(ENTERPRISE,0,"postgres.owner","隔离测试已核验",admin()));
        assertEquals("CREATING",service.get(ENTERPRISE,admin()).status());
        provider.failAfterCreate=false;
        var created=service.resume(ENTERPRISE,0,"恢复同一任务",admin());
        assertEquals(ENTERPRISE,jdbc.queryForObject("SELECT enterprise_id FROM user_account WHERE external_subject=?",UUID.class,provider.subject));
        var grants=new ManagedEnterpriseAuthorities(named);
        var old=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test").header("alg","RS256").subject(provider.subject).issuedAt(Instant.now().minusSeconds(5)).build();
        assertTrue(grants.owner(old));
        var reset=service.reset(ENTERPRISE,created.account().version(),"隔离测试重置",admin());
        assertNotEquals(created.temporaryPassword(),reset.temporaryPassword());assertThrows(RuntimeException.class,()->grants.owner(old));
        assertEquals(1,provider.created);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM enterprise_owner_invitation",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='ENTERPRISE_ACCOUNT_CREATED'",Integer.class));
        assertFalse(jdbc.queryForList("SELECT details FROM audit_log").toString().contains(reset.temporaryPassword()));
    }
}
