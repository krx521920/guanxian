package com.guanxian.platform.bootstrap;

import com.guanxian.platform.policy.PolicyCandidateMatcher;
import com.guanxian.platform.policy.PolicyCandidateMatcher.Candidate;
import com.guanxian.platform.policy.PolicyCandidateMatcher.Enterprise;
import com.guanxian.platform.policy.PolicyCandidateMatcher.Metadata;
import com.guanxian.platform.policy.PolicyService;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "postgres", matchIfMissing = true)
public class PolicyCandidatesController {
    private static final int SCAN_LIMIT = 2000;
    private final NamedParameterJdbcTemplate jdbc;
    private final ActorScopeResolver scopes;
    private final PolicyService policies;

    public PolicyCandidatesController(NamedParameterJdbcTemplate jdbc, ActorScopeResolver scopes, PolicyService policies) {
        this.jdbc = jdbc; this.scopes = scopes; this.policies = policies;
    }

    public record Page(UUID policyId, long policyVersion, String policyTitle, String method,
                       UUID associationId, String associationName, boolean ownEnterpriseOnly, String query,
                       long eligibleEnterpriseCount, int examinedCount, boolean truncated,
                       List<Candidate> items, long total, int page, int size, Instant generatedAt,
                       Metadata policyMetadata, List<String> limitations) { }

    public record OverviewItem(UUID policyId, String policyTitle, UUID associationId,
                               long candidateCount, List<Candidate> examples) { }
    public record Overview(List<OverviewItem> items, long visiblePolicyCount, int page, int size,
                           int examinedEnterpriseCount, boolean truncated, Instant generatedAt) { }
    private record ScopedEnterprise(UUID associationId, Enterprise enterprise) { }

    /** Computes associations on read; never materializes approvals, notifications or synthetic business records. */
    @GetMapping("/api/v1/policy-enterprise-candidates")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','ASSOCIATION_ADMIN','ASSOCIATION_OPERATOR','ENTERPRISE_ADMIN','ENTERPRISE_MEMBER')")
    @Transactional(readOnly = true, timeout = 15)
    public ApiResponse<Overview> overview(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size,
            Authentication authentication) {
        if (q.length() > 100 || page < 0 || page > 10000 || size < 1 || size > 20) {
            throw new ApiException("INVALID_POLICY_CANDIDATE_QUERY", "候选查询参数不正确", HttpStatus.BAD_REQUEST);
        }
        var actor = scopes.resolve(authentication);
        if (!actor.isSystemAdmin() && (actor.associationId() == null
                || !actor.isAssociationStaff() && actor.enterpriseId() == null)) {
            throw new ForbiddenException("POLICY_CANDIDATE_SCOPE_REQUIRED", "需要有效的协会或企业绑定");
        }
        // Reuse policy visibility, including sharing rules. SQL query count does not grow with policy count.
        var visible = policies.page(actor, q.strip(), false, page, size);
        var published = visible.items().stream().filter(p -> "PUBLISHED".equals(p.status()) && !p.disabled() && !p.deleted()).toList();
        if (published.isEmpty()) return ApiResponse.ok(new Overview(List.of(), visible.total(), page, size, 0, false, Instant.now()));
        var associations = new java.util.HashSet<UUID>();
        published.forEach(p -> associations.add(p.associationId()));
        if (actor.associationId() != null) associations.add(actor.associationId());
        var params = new MapSqlParameterSource("associations", associations)
                .addValue("policies", published.stream().map(p -> UUID.fromString(p.id())).toList())
                .addValue("enterprise", actor.enterpriseId()).addValue("limit", SCAN_LIMIT);
        var active = jdbc.query("SELECT id FROM association WHERE id IN (:associations) AND status='ACTIVE'",
                params, (rs,row) -> rs.getObject("id", UUID.class));
        var allowed = actor.associationId() == null ? active
                : active.contains(actor.associationId()) ? List.of(actor.associationId()) : List.<UUID>of();
        if (allowed.isEmpty()) return ApiResponse.ok(new Overview(List.of(), visible.total(), page, size, 0, false, Instant.now()));
        params.addValue("allowed", allowed);
        String from = " FROM enterprise e WHERE e.association_id IN (:allowed) AND e.status='ACTIVE' AND e.deleted_at IS NULL"
                + (actor.enterpriseId() == null ? "" : " AND e.id=:enterprise");
        long count = jdbc.queryForObject("SELECT count(*)" + from, params, Long.class);
        var enterprises = jdbc.query("""
                SELECT e.association_id,e.id,e.name,e.category,e.description,e.capabilities::text AS capabilities,
                       e.products::text AS products,e.service_scenarios::text AS scenarios,e.version,e.enterprise_roles::text AS roles
                """ + from + " ORDER BY e.id LIMIT :limit", params, (rs,row) -> new ScopedEnterprise(
                rs.getObject("association_id", UUID.class), new Enterprise(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("category"), rs.getString("description"), rs.getString("capabilities"), rs.getString("products"),
                rs.getString("scenarios"), rs.getLong("version"), rs.getString("roles"))));
        var metadata = new java.util.HashMap<UUID, Metadata>();
        jdbc.query("""
                SELECT DISTINCT ON (p.id) p.id,s.payload->'source'->>'涉及领域' AS domains,
                       s.payload->'source'->>'适用对象' AS audience,s.payload->'source'->>'适用地区' AS region,
                       s.payload->'source'->>'核验日期' AS checked,s.payload->'source'->>'当前状态' AS source_status
                  FROM policy_document p JOIN platform_source_record s ON s.policy_id=p.id
                   AND s.association_id=p.association_id AND s.kind='POLICY'
                 WHERE p.id IN (:policies) AND s.payload->>'title'=p.title
                   AND s.payload->>'summary' IS NOT DISTINCT FROM p.summary
                   AND s.payload->>'sourceUrl' IS NOT DISTINCT FROM p.source_url
                   AND s.payload->>'category' IS NOT DISTINCT FROM p.category
                   AND s.payload->>'region' IS NOT DISTINCT FROM p.policy_level
                   AND s.payload->>'effectiveOn' IS NOT DISTINCT FROM p.effective_on::text
                   AND p.tags=jsonb_build_array(s.payload->'source'->>'涉及领域')
                 ORDER BY p.id,s.created_at DESC,s.id
                """, params, (org.springframework.jdbc.core.RowCallbackHandler) rs -> metadata.put(rs.getObject("id", UUID.class),
                new Metadata(rs.getString("domains"),rs.getString("audience"),rs.getString("region"),
                        rs.getString("checked"),rs.getString("source_status"))));
        var items = published.stream().filter(p -> active.contains(p.associationId())).map(p -> {
            UUID id = UUID.fromString(p.id());
            UUID association = actor.associationId() == null ? p.associationId() : actor.associationId();
            var info = metadata.getOrDefault(id, new Metadata(null,null,null,null));
            var matches = enterprises.stream().filter(e -> association.equals(e.associationId()))
                    .flatMap(e -> PolicyCandidateMatcher.match(p, info, e.enterprise()).stream())
                    .sorted(PolicyCandidateMatcher.candidateOrder()).toList();
            return new OverviewItem(id, p.title(), association, matches.size(), matches.stream().limit(3).toList());
        }).toList();
        return ApiResponse.ok(new Overview(items, visible.total(), page, size, enterprises.size(), count > SCAN_LIMIT, Instant.now()));
    }

    @GetMapping("/api/v1/policies/{id}/enterprise-candidates")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','ASSOCIATION_ADMIN','ASSOCIATION_OPERATOR','ENTERPRISE_ADMIN','ENTERPRISE_MEMBER')")
    @Transactional(readOnly = true, timeout = 10)
    public ApiResponse<Page> page(@PathVariable UUID id, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        if (q.length() > 100 || page < 0 || page > 10000 || size < 1 || size > 100) {
            throw new ApiException("INVALID_POLICY_CANDIDATE_QUERY", "候选查询参数不正确", HttpStatus.BAD_REQUEST);
        }
        var actor = scopes.resolve(authentication);
        boolean staff = actor.isSystemAdmin() || actor.isAssociationStaff();
        if (!actor.isSystemAdmin() && (actor.associationId() == null || !staff && actor.enterpriseId() == null)) {
            throw new ForbiddenException("POLICY_CANDIDATE_SCOPE_REQUIRED", "需要有效的协会或企业绑定");
        }
        var policy = policies.get(id, actor, false); // Uses the same policy visibility rules as its detail page.
        if (!"PUBLISHED".equals(policy.status()) || policy.disabled() || policy.deleted()) {
            throw new PreconditionFailedException("仅可对已发布且未停用的政策查看候选关联");
        }
        // Public/partner policies may be visible from another association, but their members are not.
        UUID association = actor.associationId() == null ? policy.associationId() : actor.associationId();
        var params = new MapSqlParameterSource("association", association).addValue("policyAssociation", policy.associationId())
                .addValue("enterprise", actor.enterpriseId()).addValue("q", q.strip()).addValue("limit", SCAN_LIMIT)
                .addValue("policy", id).addValue("title", policy.title()).addValue("summary", policy.summary())
                .addValue("url", policy.sourceUrl()).addValue("category", policy.category()).addValue("level", policy.level())
                .addValue("effective", policy.effectiveDate() == null ? null : policy.effectiveDate().toString());
        var associations = jdbc.query("SELECT name FROM association WHERE id=:association AND status='ACTIVE'",
                params, (rs, row) -> rs.getString("name"));
        if (associations.isEmpty() || !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM association WHERE id=:policyAssociation AND status='ACTIVE')", params, Boolean.class))) {
            throw new NotFoundException("policy", id);
        }
        // Never combine an edited policy with stale applicability metadata from its original import.
        var snapshots = jdbc.query("""
                SELECT payload->'source'->>'涉及领域' AS domains, payload->'source'->>'适用对象' AS audience,
                       payload->'source'->>'适用地区' AS region, payload->'source'->>'核验日期' AS checked,
                       payload->'source'->>'当前状态' AS source_status
                  FROM platform_source_record
                 WHERE policy_id=:policy AND association_id=:policyAssociation AND kind='POLICY'
                   AND payload->>'title'=:title
                   AND payload->>'summary' IS NOT DISTINCT FROM CAST(:summary AS text)
                   AND payload->>'sourceUrl' IS NOT DISTINCT FROM CAST(:url AS text)
                   AND payload->>'category' IS NOT DISTINCT FROM CAST(:category AS text)
                   AND payload->>'region' IS NOT DISTINCT FROM CAST(:level AS text)
                   AND payload->>'effectiveOn' IS NOT DISTINCT FROM CAST(:effective AS text)
                 ORDER BY created_at DESC,id LIMIT 1
                """, params, (rs, row) -> new Metadata(rs.getString("domains"), rs.getString("audience"),
                rs.getString("region"), rs.getString("checked"), rs.getString("source_status")));
        Metadata snapshot = snapshots.isEmpty() ? null : snapshots.getFirst();
        Metadata metadata = snapshot != null && snapshot.domains() != null && List.of(snapshot.domains()).equals(policy.tags())
                ? snapshot : new Metadata(null, null, null, null);
        String from = " FROM enterprise e WHERE e.association_id=:association AND e.status='ACTIVE' AND e.deleted_at IS NULL"
                + (actor.enterpriseId() == null ? "" : " AND e.id=:enterprise")
                + " AND (:q='' OR position(lower(:q) in lower(e.name))>0)";
        long eligible = jdbc.queryForObject("SELECT count(*)" + from, params, Long.class);
        var enterprises = jdbc.query("""
                SELECT e.id,e.name,e.category,e.description,e.capabilities::text AS capabilities,
                       e.products::text AS products,e.service_scenarios::text AS scenarios,e.version,e.enterprise_roles::text AS roles
                """ + from + " ORDER BY e.id LIMIT :limit", params,
                (rs, row) -> new Enterprise(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("category"),
                        rs.getString("description"), rs.getString("capabilities"), rs.getString("products"),
                        rs.getString("scenarios"), rs.getLong("version"), rs.getString("roles")));
        var candidates = enterprises.stream().flatMap(e -> PolicyCandidateMatcher.match(policy, metadata, e).stream())
                .sorted(PolicyCandidateMatcher.candidateOrder()).toList();
        var items = candidates.stream().skip((long) page * size).limit(size).toList();
        return ApiResponse.ok(new Page(id, policy.version(), policy.title(), "PROFILE_TOPIC_CANDIDATE_V1",
                association, associations.getFirst(), actor.enterpriseId() != null, q.strip(), eligible,
                enterprises.size(), eligible > SCAN_LIMIT, items, candidates.size(), page, size, Instant.now(), metadata,
                PolicyCandidateMatcher.policyGaps(policy, metadata, LocalDate.now(ZoneId.of("Asia/Shanghai")))));
    }
}
