package com.guanxian.platform.bootstrap;

import com.guanxian.platform.bootstrap.SourceDirectoryService.Kind;
import com.guanxian.platform.bootstrap.SourceDirectoryService.Page;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.UUID;

/** HTTP and assistant tools share the same scoped, allowlisted source read service. */
@RestController
@RequestMapping("/api/v1/source-directory")
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "postgres", matchIfMissing = true)
public class SourceDirectoryController {
    private final SourceDirectoryService directory;
    private final ActorScopeResolver scopes;
    public SourceDirectoryController(SourceDirectoryService directory, ActorScopeResolver scopes) {
        this.directory = directory; this.scopes = scopes;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','ASSOCIATION_ADMIN','ASSOCIATION_OPERATOR','ENTERPRISE_ADMIN','ENTERPRISE_MEMBER')")
    public ApiResponse<Page> page(@RequestParam Kind kind, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) UUID recordId, Authentication authentication) {
        return ApiResponse.ok(directory.search(kind, q, page, size, scopes.resolve(authentication), fromDate, toDate, recordId));
    }
}
