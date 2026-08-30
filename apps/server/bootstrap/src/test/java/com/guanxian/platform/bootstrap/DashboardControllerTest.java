package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ecosystem.EcosystemMatch;
import com.guanxian.platform.ecosystem.PersistedMatchView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DashboardControllerTest {

    @Test
    void legacyMatchPreservesAnAuthorizedHiddenScoreAsNull() {
        PersistedMatchView redacted = new PersistedMatchView(
                UUID.fromString("74000000-0000-0000-0000-000000000201"),
                UUID.fromString("74000000-0000-0000-0000-000000000301"),
                UUID.fromString("74000000-0000-0000-0000-000000000101"),
                UUID.fromString("74000000-0000-0000-0000-000000000102"),
                null,
                "可见需求标题",
                null,
                null,
                null,
                null,
                List.of(),
                "PENDING_CONFIRMATION",
                null,
                null,
                null,
                null,
                3,
                null);

        EcosystemMatch value = DashboardController.legacyMatch(redacted);

        assertNull(value.score());
        assertEquals("可见需求标题", value.demandTitle());
        assertEquals("", value.updatedAt());
    }
}
