package com.guanxian.platform.bootstrap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.guanxian.platform.ai.assistant.AssistantBusinessResults;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class AssistantReceiptSerializationTest {
    // Production's spring.jackson.default-property-inclusion, not Jackson's default ALWAYS.
    final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test void globalReceiptRetainsExplicitNullWhileScopedReceiptRetainsItsId() throws Exception {
        for (UUID association : java.util.Arrays.asList(null, UUID.randomUUID())) {
            var receipt = AssistantBusinessResults.Result.create("MEMBERS", "OK", "会员企业", association,
                    Map.of(), 0, List.of(), association == null);
            var wire = json.readTree(json.writeValueAsString(receipt));
            assertThat(wire.has("associationId")).isTrue();
            if (association == null) assertThat(wire.get("associationId").isNull()).isTrue();
            else assertThat(wire.get("associationId").asText()).isEqualTo(association.toString());
        }
    }

    @Test void missingSourceLinkIsExplicitNullWithoutChangingUnrelatedNullSerialization() throws Exception {
        var source = new AssistantBusinessResults.SourceReference("TENDER", "source", "evidence", "2026-09-08", null, List.of());
        var wire = json.readTree(json.writeValueAsString(source));
        assertThat(wire.has("sourceUrl")).isTrue();
        assertThat(wire.get("sourceUrl").isNull()).isTrue();
        var item = new AssistantBusinessResults.Item(UUID.randomUUID(), "测试", "MEMBER", Map.of(), List.of());
        assertThat(json.readTree(json.writeValueAsString(item)).has("source")).isFalse();
    }
}
