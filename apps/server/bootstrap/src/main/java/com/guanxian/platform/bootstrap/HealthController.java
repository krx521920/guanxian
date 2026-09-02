package com.guanxian.platform.bootstrap;

import com.guanxian.platform.shared.api.ApiResponse;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {
    private final HealthEndpoint healthEndpoint;

    public HealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping
    ResponseEntity<ApiResponse<Map<String, String>>> health() {
        Status status = healthEndpoint.health().getStatus();
        HttpStatus responseStatus = Status.UP.equals(status)
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;
        Map<String, String> data = Map.of(
                "status", status.getCode(),
                "service", "guanxian-server");
        ApiResponse<Map<String, String>> body = Status.UP.equals(status)
                ? ApiResponse.ok(data)
                : ApiResponse.error(
                        "DEPENDENCY_UNAVAILABLE", "one or more dependencies are unavailable", data);
        return ResponseEntity.status(responseStatus).body(body);
    }
}
