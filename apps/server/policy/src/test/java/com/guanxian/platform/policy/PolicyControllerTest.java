package com.guanxian.platform.policy;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.PreconditionRequiredException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyControllerTest {
    @Test
    void parsesOneStrongVersionEtag() {
        assertThat(PolicyController.requiredVersion(List.of("\"42\""))).isEqualTo(42);
    }

    @Test
    void rejectsMissingWeakOrMultipleEtags() {
        assertThatThrownBy(() -> PolicyController.requiredVersion(null))
                .isInstanceOf(PreconditionRequiredException.class);
        assertThatThrownBy(() -> PolicyController.requiredVersion(List.of("W/\"1\"")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PolicyController.requiredVersion(List.of("\"1\"", "\"2\"")))
                .isInstanceOf(ApiException.class);
    }
}
