package com.guanxian.platform.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {
    @Test
    void dependencyFailureReturnsServiceUnavailableWithoutExposingDetails() {
        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenReturn(Health.down().withDetail("secret", "hidden").build());

        var response = new HealthController(endpoint).health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(response.getBody().data())
                .containsEntry("status", "DOWN")
                .doesNotContainKey("secret");
    }
}
