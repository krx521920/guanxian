package com.guanxian.platform.policy;

import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "memory")
class InMemoryPolicyStore implements PolicyStore {
    private final ConcurrentMap<UUID, PolicyView> policies = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, List<PolicyHistoryView>> histories = new ConcurrentHashMap<>();

    InMemoryPolicyStore() {
        this(false);
    }

    @Autowired
    InMemoryPolicyStore(
            @Value("${guanxian.business.seed-demo-data:${guanxian.member.seed-demo-data:false}}")
            boolean seedDemoData) {
        if (!seedDemoData) {
            return;
        }
        seed("10000000-0000-0000-0000-000000000001", "城市地下管线建设管理工作指导意见",
                "住房和城乡建设部", "国家", "建设管理", LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 9, 1), "强化地下管线全生命周期管理，推动数字化交付与风险分级管控。",
                List.of("全生命周期", "数字化交付", "风险管理"));
        seed("10000000-0000-0000-0000-000000000002", "北京市地下管线信息管理办法（修订）",
                "北京市城市管理委员会", "北京市", "信息管理", LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 8, 20), "明确管线信息汇交、更新与共享要求，细化建设单位和权属单位责任。",
                List.of("信息汇交", "数据标准", "权属责任"));
    }

    @Override
    public List<PolicyView> list(
            ActorScope actor, String query, String level,
            boolean includeDeleted, long offset, int limit) {
        return policies.values().stream()
                .filter(policy -> includeDeleted || !policy.deleted())
                .filter(policy -> canRead(actor, policy))
                .filter(policy -> matches(query, policy))
                .filter(policy -> level == null || level.equals(policy.level()))
                .sorted(Comparator.comparing(PolicyView::updatedAt).reversed().thenComparing(PolicyView::id))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long count(ActorScope actor, String query, String level, boolean includeDeleted) {
        return policies.values().stream()
                .filter(policy -> includeDeleted || !policy.deleted())
                .filter(policy -> canRead(actor, policy))
                .filter(policy -> matches(query, policy))
                .filter(policy -> level == null || level.equals(policy.level()))
                .count();
    }

    @Override
    public List<String> levels(ActorScope actor) {
        return policies.values().stream()
                .filter(policy -> !policy.deleted())
                .filter(policy -> canRead(actor, policy))
                .map(PolicyView::level)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    @Override
    public Optional<PolicyView> find(UUID id, ActorScope actor, boolean includeDeleted) {
        PolicyView policy = policies.get(id);
        if (policy == null || (!includeDeleted && policy.deleted()) || !canRead(actor, policy)) {
            return Optional.empty();
        }
        return Optional.of(policy);
    }

    @Override
    public synchronized PolicyView create(UUID associationId, PolicyUpsertRequest request, ActorScope actor) {
        UUID id = UUID.randomUUID();
        PolicyView value = fromRequest(id, associationId, request, "DRAFT", 0, false, false);
        policies.put(id, value);
        return value;
    }

    @Override
    public synchronized Optional<PolicyView> update(
            UUID id, long expectedVersion, PolicyUpsertRequest request, ActorScope actor) {
        PolicyView old = policies.get(id);
        if (old == null || old.deleted() || old.version() != expectedVersion) {
            return Optional.empty();
        }
        PolicyView updated = fromRequest(id, old.associationId(), request, "DRAFT",
                old.version() + 1, false, false);
        policies.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<PolicyView> transition(
            UUID id, long expectedVersion, String targetStatus, ActorScope actor) {
        PolicyView old = policies.get(id);
        if (old == null || old.deleted() || old.version() != expectedVersion) {
            return Optional.empty();
        }
        PolicyView updated = copy(old, targetStatus, old.version() + 1,
                "DISABLED".equals(targetStatus), false);
        policies.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<PolicyView> softDelete(UUID id, long expectedVersion, ActorScope actor) {
        PolicyView old = policies.get(id);
        if (old == null || old.deleted() || old.version() != expectedVersion) {
            return Optional.empty();
        }
        PolicyView updated = copy(old, old.status(), old.version() + 1, old.disabled(), true);
        policies.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<PolicyView> restore(UUID id, long expectedVersion, ActorScope actor) {
        PolicyView old = policies.get(id);
        if (old == null || !old.deleted() || old.version() != expectedVersion) {
            return Optional.empty();
        }
        PolicyView updated = copy(old, "DRAFT", old.version() + 1, false, false);
        policies.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized void recordChange(
            ActorScope actor, String action, PolicyView policy, String comment) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", policy.id());
        snapshot.put("title", policy.title());
        snapshot.put("status", policy.status());
        snapshot.put("visibility", policy.visibility());
        snapshot.put("version", policy.version());
        snapshot.put("disabled", policy.disabled());
        snapshot.put("deleted", policy.deleted());
        if (comment != null && !comment.isBlank()) {
            snapshot.put("comment", comment.trim());
        }
        histories.computeIfAbsent(UUID.fromString(policy.id()), ignored -> new ArrayList<>())
                .add(new PolicyHistoryView(policy.version(), action, actor.subject(), Map.copyOf(snapshot), Instant.now()));
    }

    @Override
    public List<PolicyHistoryView> history(UUID id, ActorScope actor, int limit) {
        return histories.getOrDefault(id, List.of()).stream()
                .sorted(Comparator.comparingLong(PolicyHistoryView::version).reversed()
                        .thenComparing(PolicyHistoryView::occurredAt, Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    private void seed(String id, String title, String authority, String level, String category,
                      LocalDate publishedOn, LocalDate effectiveOn, String summary, List<String> tags) {
        UUID uuid = UUID.fromString(id);
        policies.put(uuid, new PolicyView(id, title, authority, null, level, category, publishedOn, effectiveOn,
                null, "PUBLISHED", summary, tags, null, "PUBLIC", 0, false, false, Instant.now()));
    }

    private static PolicyView fromRequest(UUID id, UUID associationId, PolicyUpsertRequest request,
                                          String status, long version, boolean disabled, boolean deleted) {
        return new PolicyView(id.toString(), request.title().trim(), clean(request.authority()),
                clean(request.documentNumber()), clean(request.level()), clean(request.category()),
                request.publishDate(), request.effectiveDate(), clean(request.sourceUrl()),
                status, clean(request.summary()), list(request.tags()), associationId,
                visibility(request.visibility()), version, disabled, deleted, Instant.now());
    }

    private static PolicyView copy(PolicyView old, String status, long version, boolean disabled, boolean deleted) {
        return new PolicyView(old.id(), old.title(), old.authority(), old.documentNumber(), old.level(), old.category(),
                old.publishDate(), old.effectiveDate(), old.sourceUrl(), status, old.summary(), old.tags(), old.associationId(),
                old.visibility(), version, disabled, deleted, Instant.now());
    }

    private static boolean canRead(ActorScope actor, PolicyView policy) {
        if (actor.isSystemAdmin()) {
            return actor.associationId() == null || actor.associationId().equals(policy.associationId());
        }
        boolean ownAssociation = actor.associationId() != null
                && actor.associationId().equals(policy.associationId());
        if (ownAssociation && actor.isAssociationStaff()) {
            return true;
        }
        if (!"PUBLISHED".equals(policy.status()) || policy.disabled()) {
            return false;
        }
        if (ownAssociation && !"PRIVATE".equals(policy.visibility())
                || "PUBLIC".equals(policy.visibility())) {
            return true;
        }
        return actor.partnerAssociationIds().contains(policy.associationId())
                && "PARTNERS".equals(policy.visibility());
    }

    private static boolean matches(String query, PolicyView policy) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return String.join(" ", nonNull(policy.title()), nonNull(policy.authority()), nonNull(policy.category()),
                nonNull(policy.summary()), String.join(" ", policy.tags()))
                .toLowerCase(Locale.ROOT).contains(needle);
    }

    private static List<String> list(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String visibility(String value) {
        return value == null || value.isBlank() ? "MEMBERS" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
