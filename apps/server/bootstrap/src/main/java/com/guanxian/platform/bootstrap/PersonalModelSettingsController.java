package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.assistant.PersonalModelProvider;
import com.guanxian.platform.ai.assistant.PersonalModelSettingsService;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1/assistant/model-settings")
@PreAuthorize("hasAuthority('POLICY_READ')")
public class PersonalModelSettingsController {
    private final PersonalModelSettingsService service;
    private final ActorScopeResolver resolver;
    public PersonalModelSettingsController(PersonalModelSettingsService service, ActorScopeResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    @GetMapping
    ApiResponse<PersonalModelSettingsService.SettingsView> get(Authentication auth, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return mapped(() -> service.settings(resolver.resolve(auth).userId()));
    }

    @PutMapping
    ApiResponse<PersonalModelSettingsService.SettingsView> save(@Valid @RequestBody SaveRequest request,
                                                              Authentication auth, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return mapped(() -> service.save(resolver.resolve(auth).userId(), request.provider(), request.model(),
                request.apiKey(), request.enabled(), request.externalDataConsent()));
    }

    @DeleteMapping
    ApiResponse<Boolean> delete(Authentication auth, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return mapped(() -> { service.delete(resolver.resolve(auth).userId()); return true; });
    }

    @PostMapping("/test")
    ApiResponse<PersonalModelSettingsService.TestResult> test(@Valid @RequestBody TestRequest request,
                                                            Authentication auth, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return mapped(() -> service.test(resolver.resolve(auth).userId(), request.externalDataConsent()));
    }

    private static <T> ApiResponse<T> mapped(Supplier<T> action) {
        try {
            return ApiResponse.ok(action.get());
        } catch (IllegalArgumentException exception) {
            throw new ApiException("INVALID_MODEL_SETTINGS", exception.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (IllegalStateException exception) {
            throw new ApiException("MODEL_SETTINGS_UNAVAILABLE", "模型配置暂不可用，请检查加密服务与平台数据外发设置", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public record SaveRequest(@NotNull PersonalModelProvider provider, @NotBlank @Size(max = 160) String model,
                              @Size(max = 4096) String apiKey, boolean enabled, boolean externalDataConsent) {
        @Override public String toString() { return "PersonalModelSaveRequest[redacted]"; }
    }
    public record TestRequest(boolean externalDataConsent) { }
}
