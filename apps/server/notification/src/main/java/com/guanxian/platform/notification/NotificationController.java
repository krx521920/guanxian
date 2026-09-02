package com.guanxian.platform.notification;

import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.PreconditionRequiredException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private static final Pattern STRONG_ETAG = Pattern.compile("\\\"(0|[1-9][0-9]*)\\\"");

    private final NotificationService service;
    private final ActorScopeResolver actorScopes;

    public NotificationController(NotificationService service, ActorScopeResolver actorScopes) {
        this.service = service;
        this.actorScopes = actorScopes;
    }

    @GetMapping("/subscriptions")
    @PreAuthorize("hasAuthority('NOTIFICATION_READ')")
    ApiResponse<List<SubscriptionView>> subscriptions(Authentication authentication) {
        return ApiResponse.ok(service.subscriptions(actor(authentication)));
    }

    @PostMapping("/subscriptions")
    @PreAuthorize("hasAuthority('NOTIFICATION_READ')")
    ResponseEntity<ApiResponse<SubscriptionView>> createSubscription(
            @Valid @RequestBody SubscriptionRequest request, Authentication authentication) {
        return subscriptionResponse(HttpStatus.CREATED, service.createSubscription(request, actor(authentication)));
    }

    @PutMapping("/subscriptions/{id}")
    @PreAuthorize("hasAuthority('NOTIFICATION_READ')")
    ResponseEntity<ApiResponse<SubscriptionView>> updateSubscription(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            @Valid @RequestBody SubscriptionRequest request,
            Authentication authentication) {
        return subscriptionResponse(HttpStatus.OK,
                service.updateSubscription(id, requiredVersion(ifMatch), request, actor(authentication)));
    }

    @PutMapping("/subscriptions/{id}/disable")
    @PreAuthorize("hasAuthority('NOTIFICATION_READ')")
    ResponseEntity<ApiResponse<SubscriptionView>> disableSubscription(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        return subscriptionResponse(HttpStatus.OK,
                service.disableSubscription(id, requiredVersion(ifMatch), actor(authentication)));
    }

    @PutMapping("/subscriptions/{id}/restore")
    @PreAuthorize("hasAuthority('NOTIFICATION_READ')")
    ResponseEntity<ApiResponse<SubscriptionView>> restoreSubscription(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        return subscriptionResponse(HttpStatus.OK,
                service.restoreSubscription(id, requiredVersion(ifMatch), actor(authentication)));
    }

    @DeleteMapping("/subscriptions/{id}")
    @PreAuthorize("hasAuthority('NOTIFICATION_READ')")
    ResponseEntity<Void> deleteSubscription(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        service.deleteSubscription(id, requiredVersion(ifMatch), actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/messages")
    @PreAuthorize("hasAuthority('NOTIFICATION_READ')")
    ApiResponse<NotificationMessagePage> messages(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ApiResponse.ok(service.messages(actor(authentication), unreadOnly, status, page, size));
    }

    @PutMapping("/messages/{id}/read")
    @PreAuthorize("hasAuthority('NOTIFICATION_READ')")
    ApiResponse<NotificationMessageView> markRead(
            @PathVariable UUID id, Authentication authentication) {
        return ApiResponse.ok(service.markRead(id, actor(authentication)));
    }

    @PutMapping("/messages/{id}/archive")
    @PreAuthorize("hasAuthority('NOTIFICATION_READ')")
    ApiResponse<NotificationMessageView> archive(
            @PathVariable UUID id, Authentication authentication) {
        return ApiResponse.ok(service.archive(id, actor(authentication)));
    }

    @PutMapping("/messages/{id}/restore")
    @PreAuthorize("hasAuthority('NOTIFICATION_READ')")
    ApiResponse<NotificationMessageView> restore(
            @PathVariable UUID id, Authentication authentication) {
        return ApiResponse.ok(service.restore(id, actor(authentication)));
    }

    @PostMapping("/policies")
    @PreAuthorize("hasAuthority('NOTIFICATION_PUBLISH')")
    ResponseEntity<ApiResponse<PolicyNotificationResult>> publishPolicy(
            @Valid @RequestBody PolicyNotificationRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(service.publishPolicy(request, actor(authentication))));
    }

    private ActorScope actor(Authentication authentication) {
        return actorScopes.resolve(authentication);
    }

    private static ResponseEntity<ApiResponse<SubscriptionView>> subscriptionResponse(
            HttpStatus status, SubscriptionView value) {
        return ResponseEntity.status(status).eTag('"' + Long.toString(value.version()) + '"')
                .body(ApiResponse.ok(value));
    }

    static long requiredVersion(List<String> values) {
        if (values == null || values.size() != 1) {
            if (values == null || values.isEmpty()) {
                throw new PreconditionRequiredException("If-Match header is required");
            }
            throw invalidEtag();
        }
        Matcher matcher = STRONG_ETAG.matcher(values.getFirst());
        if (!matcher.matches()) {
            throw invalidEtag();
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw invalidEtag();
        }
    }

    private static ApiException invalidEtag() {
        return new ApiException("INVALID_IF_MATCH", "If-Match must be one strong version ETag",
                HttpStatus.BAD_REQUEST);
    }
}
