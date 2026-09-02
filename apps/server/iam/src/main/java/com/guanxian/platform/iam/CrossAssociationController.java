package com.guanxian.platform.iam;

import com.guanxian.platform.shared.api.ApiResponse;
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

@RestController
@RequestMapping("/api/v1/cross-associations")
class CrossAssociationController {
    private final CrossAssociationService service;
    private final ActorScopeResolver actorScopes;

    CrossAssociationController(CrossAssociationService service, ActorScopeResolver actorScopes) {
        this.service = service;
        this.actorScopes = actorScopes;
    }

    @GetMapping("/access-requests")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR')")
    ApiResponse<List<CrossAssociationDtos.AccessRequestView>> accessRequests(Authentication authentication) {
        return ApiResponse.ok(service.accessRequests(actor(authentication)));
    }

    @GetMapping("/access-requests/page")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR')")
    ApiResponse<CrossAssociationPage<CrossAssociationDtos.AccessRequestView>> accessRequestsPage(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ApiResponse.ok(service.accessRequestsPage(actor(authentication), page, size));
    }

    @PostMapping("/access-requests")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<CrossAssociationDtos.AccessRequestView>> createAccessRequest(
            @Valid @RequestBody CrossAssociationDtos.AccessRequestCreate request, Authentication authentication) {
        var value = service.createAccessRequest(request, actor(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(value.version())).body(ApiResponse.ok(value));
    }

    @PutMapping("/access-requests/{id}/review")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<CrossAssociationDtos.AccessRequestView>> reviewAccessRequest(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            @Valid @RequestBody CrossAssociationDtos.AccessRequestReview request,
            Authentication authentication) {
        var value = service.reviewAccessRequest(
                id, VersionEtags.requiredVersion(ifMatch), request, actor(authentication));
        return versioned(value.version(), value);
    }

    @PutMapping("/access-requests/{id}/cancel")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<CrossAssociationDtos.AccessRequestView>> cancelAccessRequest(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            @Valid @RequestBody CrossAssociationDtos.AccessRequestCancel request,
            Authentication authentication) {
        var value = service.cancelAccessRequest(
                id, VersionEtags.requiredVersion(ifMatch), request, actor(authentication));
        return versioned(value.version(), value);
    }

    @GetMapping("/relationships")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR')")
    ApiResponse<List<CrossAssociationDtos.RelationshipView>> relationships(Authentication authentication) {
        return ApiResponse.ok(service.relationships(actor(authentication)));
    }

    @GetMapping("/relationships/page")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR')")
    ApiResponse<CrossAssociationPage<CrossAssociationDtos.RelationshipView>> relationshipsPage(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ApiResponse.ok(service.relationshipsPage(actor(authentication), page, size));
    }

    @PutMapping("/relationships/{source}/{target}")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<CrossAssociationDtos.RelationshipView>> changeRelationship(
            @PathVariable UUID source,
            @PathVariable UUID target,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            @Valid @RequestBody CrossAssociationDtos.RelationshipChange request,
            Authentication authentication) {
        var value = service.changeRelationship(source, target, VersionEtags.requiredVersion(ifMatch),
                request, actor(authentication));
        return versioned(value.version(), value);
    }

    @GetMapping("/share-policies")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR')")
    ApiResponse<List<CrossAssociationDtos.SharePolicyView>> sharePolicies(Authentication authentication) {
        return ApiResponse.ok(service.sharePolicies(actor(authentication)));
    }

    @GetMapping("/share-policies/page")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR')")
    ApiResponse<CrossAssociationPage<CrossAssociationDtos.SharePolicyView>> sharePoliciesPage(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ApiResponse.ok(service.sharePoliciesPage(actor(authentication), page, size));
    }

    @PostMapping("/share-policies")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<CrossAssociationDtos.SharePolicyView>> createSharePolicy(
            @Valid @RequestBody CrossAssociationDtos.SharePolicyUpsert request, Authentication authentication) {
        var value = service.createSharePolicy(request, actor(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(value.version())).body(ApiResponse.ok(value));
    }

    @PutMapping("/share-policies/{id}")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<CrossAssociationDtos.SharePolicyView>> updateSharePolicy(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            @Valid @RequestBody CrossAssociationDtos.SharePolicyUpsert request,
            Authentication authentication) {
        var value = service.updateSharePolicy(id, VersionEtags.requiredVersion(ifMatch), request, actor(authentication));
        return versioned(value.version(), value);
    }

    @PutMapping("/share-policies/{id}/status")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<CrossAssociationDtos.SharePolicyView>> changeSharePolicyStatus(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            @Valid @RequestBody CrossAssociationDtos.SharePolicyStatusChange request,
            Authentication authentication) {
        var value = service.changeSharePolicyStatus(
                id, VersionEtags.requiredVersion(ifMatch), request, actor(authentication));
        return versioned(value.version(), value);
    }

    @GetMapping("/consents")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ApiResponse<List<CrossAssociationDtos.ConsentView>> consents(Authentication authentication) {
        return ApiResponse.ok(service.consents(actor(authentication)));
    }

    @GetMapping("/consents/page")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ApiResponse<CrossAssociationPage<CrossAssociationDtos.ConsentView>> consentsPage(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ApiResponse.ok(service.consentsPage(actor(authentication), page, size));
    }

    @GetMapping("/consent-targets")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ApiResponse<List<CrossAssociationDtos.ConsentTargetView>> consentTargets(Authentication authentication) {
        return ApiResponse.ok(service.consentTargets(actor(authentication)));
    }

    @PostMapping("/consents")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<CrossAssociationDtos.ConsentView>> grantConsent(
            @Valid @RequestBody CrossAssociationDtos.ConsentCreate request, Authentication authentication) {
        var value = service.grantConsent(request, actor(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(value.version())).body(ApiResponse.ok(value));
    }

    @DeleteMapping("/consents/{id}")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<CrossAssociationDtos.ConsentView>> revokeConsent(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        var value = service.revokeConsent(id, VersionEtags.requiredVersion(ifMatch), actor(authentication));
        return versioned(value.version(), value);
    }

    @GetMapping("/recommendations")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ApiResponse<List<CrossAssociationDtos.RecommendationView>> recommendations(Authentication authentication) {
        return ApiResponse.ok(service.recommendations(actor(authentication)));
    }

    @GetMapping("/recommendations/page")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ApiResponse<CrossAssociationPage<CrossAssociationDtos.RecommendationView>> recommendationsPage(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ApiResponse.ok(service.recommendationsPage(actor(authentication), page, size));
    }

    @PostMapping("/recommendations")
    @PreAuthorize("hasAnyAuthority('MEMBER_REVIEW', 'ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<CrossAssociationDtos.RecommendationView>> createRecommendation(
            @Valid @RequestBody CrossAssociationDtos.RecommendationCreate request, Authentication authentication) {
        var value = service.createRecommendation(request, actor(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(value.version())).body(ApiResponse.ok(value));
    }

    @PutMapping("/recommendations/{id}/review")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<CrossAssociationDtos.RecommendationView>> reviewRecommendation(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            @Valid @RequestBody CrossAssociationDtos.RecommendationReview request,
            Authentication authentication) {
        var value = service.reviewRecommendation(id, VersionEtags.requiredVersion(ifMatch),
                request, actor(authentication));
        return versioned(value.version(), value);
    }

    private ActorScope actor(Authentication authentication) {
        return actorScopes.resolve(authentication);
    }

    private static <T> ResponseEntity<ApiResponse<T>> versioned(long version, T value) {
        return ResponseEntity.ok().eTag(etag(version)).body(ApiResponse.ok(value));
    }

    private static String etag(long version) {
        return '"' + Long.toString(version) + '"';
    }
}
