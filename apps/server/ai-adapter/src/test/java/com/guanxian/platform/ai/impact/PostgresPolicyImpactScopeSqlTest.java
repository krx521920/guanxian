package com.guanxian.platform.ai.impact;

import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.ReadScope;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresPolicyImpactScopeSqlTest {
    private static final UUID ASSOCIATION_ID = UUID.randomUUID();
    private static final UUID ENTERPRISE_ID = UUID.randomUUID();

    @Test
    void lifecycleFilteringSeparatesEnterpriseReadsFromAdministratorHistory() throws Exception {
        String enterpriseSql = where(new ReadScope(
                false, ASSOCIATION_ID, ENTERPRISE_ID, false));
        assertTrue(enterpriseSql.contains("enterprise.status = 'ACTIVE'"));

        String associationSql = where(new ReadScope(
                false, ASSOCIATION_ID, null, true));
        assertFalse(associationSql.contains("enterprise.status = 'ACTIVE'"));
        assertTrue(associationSql.contains("enterprise.association_id = :associationId"));

        String systemSql = where(new ReadScope(
                true, ASSOCIATION_ID, ENTERPRISE_ID, false));
        assertFalse(systemSql.contains("enterprise.status = 'ACTIVE'"));
        assertTrue(systemSql.contains("analysis.enterprise_id = :scopeEnterpriseId"));
        assertTrue(systemSql.contains("enterprise.association_id = :associationId"));
    }

    private static String where(ReadScope scope) throws Exception {
        Method method = PostgresPolicyImpactAnalysisStore.class.getDeclaredMethod(
                "where", ReadScope.class, String.class, UUID.class, UUID.class);
        method.setAccessible(true);
        return (String) method.invoke(null, scope, null, null, null);
    }
}
