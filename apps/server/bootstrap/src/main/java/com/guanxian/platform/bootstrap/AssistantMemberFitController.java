package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.assistant.AssistantAccessContext;
import com.guanxian.platform.ai.assistant.AssistantBusinessResults;
import com.guanxian.platform.ai.assistant.AssistantEnterpriseSelection;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.stream.Collectors;

/** POST carries bounded read-only criteria; it never writes a member or creates a match. */
@RestController
@RequestMapping("/api/v1/assistant/members")
public class AssistantMemberFitController {
    private final AssistantBusinessQueryTools tools;
    private final ActorScopeResolver scopes;
    public AssistantMemberFitController(AssistantBusinessQueryTools tools, ActorScopeResolver scopes) {
        this.tools = tools; this.scopes = scopes;
    }

    @PostMapping("/fit-check")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ApiResponse<FitCheckResponse> check(@Valid @RequestBody FitCheckRequest request, Authentication authentication,
                                      HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var actor = scopes.resolve(authentication);
        AssistantController.readAssociationId(request.associationId(), actor);
        var access = new AssistantAccessContext(actor, authentication.getAuthorities().stream()
                .map(a -> a.getAuthority()).collect(Collectors.toUnmodifiableSet()));
        try {
            var ids = AssistantEnterpriseSelection.validate(request.enterpriseIds());
            var criteria = request.criteria().stream().map(c -> new AssistantBusinessQueryTools.FitCriterion(c.field(), c.value().trim())).toList();
            if (criteria.stream().map(c -> c.field() + "\n" + c.value().toLowerCase(Locale.ROOT)).distinct().count() != criteria.size()
                    || criteria.stream().anyMatch(c -> "status".equals(c.field()) && !Set.of("ACTIVE", "PENDING_REVIEW", "INCOMPLETE", "DISABLED", "DELETED").contains(c.value()))) {
                throw new IllegalArgumentException("invalid criteria");
            }
            return ApiResponse.ok(new FitCheckResponse(criteria, tools.checkMemberFit(ids, criteria, access)));
        } catch (IllegalArgumentException exception) {
            throw new ApiException("INVALID_FIT_CHECK", "请选择不同企业，检查重复条件及审核状态", HttpStatus.BAD_REQUEST);
        }
    }

    public record Criterion(@NotBlank @Pattern(regexp = "category|status|capabilities|products|services") String field,
                            @NotBlank @Size(max = 80) String value) {}
    public record FitCheckRequest(UUID associationId,
            @NotNull @Size(min = 1, max = 4) List<@NotNull UUID> enterpriseIds,
            @NotNull @Size(min = 1, max = 8) List<@NotNull @Valid Criterion> criteria) {}
    public record FitCheckResponse(List<AssistantBusinessQueryTools.FitCriterion> criteria, AssistantBusinessResults.Result result) {}
}
