package com.guanxian.platform.shared.security;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the business fields that may cross an association boundary for one
 * concrete resource. An empty optional means that the resource itself is not
 * authorized for the caller; structural identifiers must not be returned in
 * that case.
 */
@FunctionalInterface
public interface PartnerFieldAuthorization {
    Set<String> MEMBER_FIELDS = Set.of(
            "name", "category", "address", "introduction",
            "capabilities", "products", "cooperationNeeds");
    Set<String> OFFERING_FIELDS = Set.of(
            "enterpriseName", "name", "description", "scenarios", "qualifications");
    Set<String> DEMAND_FIELDS = Set.of(
            "enterpriseName", "title", "description", "scenarios", "requiredCapabilities",
            "budgetMin", "budgetMax", "responseDeadline");
    Set<String> MATCH_FIELDS = Set.of(
            "demandCompany", "demandTitle", "scene", "supplierCompany",
            "solution", "score", "reasons", "state", "outcomes");

    Optional<Set<String>> authorizedFields(
            ActorScope actor, UUID enterpriseId, String resourceType, UUID resourceId);

    static Set<String> allowedFields(String resourceType) {
        return switch (resourceType) {
            case "MEMBER" -> MEMBER_FIELDS;
            case "PRODUCT", "SERVICE" -> OFFERING_FIELDS;
            case "DEMAND" -> DEMAND_FIELDS;
            case "MATCH" -> MATCH_FIELDS;
            default -> Set.of();
        };
    }

    static Set<String> requiredFields(String resourceType) {
        return switch (resourceType) {
            case "MEMBER", "PRODUCT", "SERVICE" -> Set.of("name");
            case "DEMAND" -> Set.of("title");
            default -> Set.of();
        };
    }

    /** Test/local constructor fallback; production wiring supplies the IAM implementation. */
    static PartnerFieldAuthorization allowAll() {
        return (actor, enterpriseId, resourceType, resourceId) -> {
            Set<String> allowed = allowedFields(resourceType);
            return allowed.isEmpty() ? Optional.empty() : Optional.of(allowed);
        };
    }
}
