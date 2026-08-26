package com.guanxian.platform;

import com.guanxian.platform.bootstrap.PolicyImpactController;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.PreconditionRequiredException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyImpactControllerTest {
    @Test
    void parsesOneStrongVersionEtag() {
        assertThat(PolicyImpactController.requiredVersion(List.of("\"42\""))).isEqualTo(42);
    }

    @Test
    void rejectsMissingWeakWildcardOrMultipleEtags() {
        assertThatThrownBy(() -> PolicyImpactController.requiredVersion(null))
                .isInstanceOf(PreconditionRequiredException.class);
        assertThatThrownBy(() -> PolicyImpactController.requiredVersion(List.of("W/\"1\"")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PolicyImpactController.requiredVersion(List.of("*")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PolicyImpactController.requiredVersion(List.of("\"1\"", "\"2\"")))
                .isInstanceOf(ApiException.class);
    }
}
