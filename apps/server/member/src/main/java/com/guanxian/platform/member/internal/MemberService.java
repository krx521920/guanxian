package com.guanxian.platform.member.internal;

import com.guanxian.platform.member.api.MemberDirectory;
import com.guanxian.platform.member.api.EnterpriseLifecycle;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.web.MemberReviewRequest;
import com.guanxian.platform.member.web.MemberUpsertRequest;
import com.guanxian.platform.shared.error.ConflictException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.PartnerFieldAuthorization;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class MemberService implements MemberDirectory, EnterpriseLifecycle {
    public static final UUID DEMO_PRIMARY_ENTERPRISE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000201");
    public static final UUID DEMO_SECONDARY_ENTERPRISE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final ActorScope SYSTEM_ACTOR = new ActorScope(
            null, "internal-system", "internal-system", null, null, Set.of("SYSTEM_ADMIN"), Set.of());

    private final MemberRepository repository;
    private final AuditTrail auditTrail;
    private final PartnerFieldAuthorization partnerFieldAuthorization;
    @Value("${guanxian.member.seed-demo-data:false}")
    private boolean seedDemoData;

    @Autowired
    MemberService(
            MemberRepository repository,
            AuditTrail auditTrail,
            PartnerFieldAuthorization partnerFieldAuthorization) {
        this.repository = repository;
        this.auditTrail = auditTrail;
        this.partnerFieldAuthorization = partnerFieldAuthorization;
    }

    MemberService(MemberRepository repository, AuditTrail auditTrail) {
        this(repository, auditTrail, PartnerFieldAuthorization.allowAll());
    }

    MemberService(MemberRepository repository) {
        this(repository, new NoopAuditTrail());
    }

    @PostConstruct
    void seed() {
        if (!seedDemoData || !repository.findAll().isEmpty()) {
            return;
        }
        UUID associationId = repository.defaultAssociationId();
        insertSeed(DEMO_PRIMARY_ENTERPRISE_ID, associationId, new MemberUpsertRequest(
                "京城管网科技有限公司", "91110000DEMO00001", "智慧管网",
                "北京市海淀区", "张工", "13800000001", "提供地下管线监测与数字化平台服务",
                List.of("管线监测", "泄漏预警", "数字孪生"),
                List.of("智能监测终端", "管网数字孪生平台"),
                List.of("寻找燃气及供热场景合作方"), "ACTIVE"));
        insertSeed(DEMO_SECONDARY_ENTERPRISE_ID, associationId, new MemberUpsertRequest(
                "北方阀门制造有限公司", "91110000DEMO00002", "装备制造",
                "北京市大兴区", "李经理", "13800000002", "生产供水、燃气和热力管网阀门",
                List.of("阀门制造", "带压维护"),
                List.of("燃气球阀", "供水蝶阀"),
                List.of("对接管线施工及运营单位"), "ACTIVE"));
    }

    @Override
    public List<MemberProfile> findAll(String query, ActorScope actor) {
        return findAll(query, null, false, actor);
    }

    public List<MemberProfile> findAll(String query, String status, ActorScope actor) {
        return findAll(query, status, false, actor);
    }

    public List<MemberProfile> findAll(
            String query, String status, boolean includeDeleted, ActorScope actor) {
        String keyword = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        boolean allowDeleted = includeDeleted && (actor.isSystemAdmin() || actor.isAssociationReviewer());
        return repository.findAll().stream()
                .filter(member -> allowDeleted || !member.deleted())
                .filter(member -> MemberAccessPolicy.canRead(actor, member))
                .map(member -> authorizeRead(actor, member))
                .flatMap(Optional::stream)
                .filter(member -> normalizedStatus.isEmpty() || member.status().equals(normalizedStatus))
                .filter(member -> keyword.isEmpty() || searchableText(member).contains(keyword))
                .sorted(Comparator.comparing(MemberProfile::name).thenComparing(MemberProfile::id))
                .toList();
    }

    public List<MemberProfile> page(
            String query, String status, boolean includeDeleted, ActorScope actor, int page, int size) {
        List<MemberProfile> visible = findAll(query, status, includeDeleted, actor);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        long offset = (long) safePage * safeSize;
        if (offset >= visible.size()) {
            return List.of();
        }
        int from = Math.toIntExact(offset);
        return visible.subList(from, Math.min(visible.size(), from + safeSize));
    }

    List<MemberProfile> findAll(String query) {
        return findAll(query, SYSTEM_ACTOR);
    }
    @Override
    public Optional<MemberProfile> findById(UUID id, ActorScope actor) {
        return findById(id, actor, false);
    }

    @Override
    public boolean isOperational(UUID enterpriseId) {
        if (enterpriseId == null) {
            return false;
        }
        return repository.findById(enterpriseId)
                .filter(member -> !member.deleted())
                .filter(member -> "ACTIVE".equals(member.status()))
                .isPresent();
    }

    public Optional<MemberProfile> findById(UUID id, ActorScope actor, boolean includeDeleted) {
        boolean allowDeleted = includeDeleted && (actor.isSystemAdmin() || actor.isAssociationReviewer());
        return repository.findById(id)
                .filter(member -> allowDeleted || !member.deleted())
                .filter(member -> MemberAccessPolicy.canRead(actor, member))
                .flatMap(member -> authorizeRead(actor, member));
    }

    public MemberProfile get(UUID id, ActorScope actor) {
        return findById(id, actor).orElseThrow(() -> new NotFoundException("member", id));
    }

    public MemberProfile get(UUID id, ActorScope actor, boolean includeDeleted) {
        return findById(id, actor, includeDeleted).orElseThrow(() -> new NotFoundException("member", id));
    }

    MemberProfile get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("member", id));
    }

    @Transactional
    public synchronized MemberProfile create(MemberUpsertRequest request, ActorScope actor) {
        UUID associationId = targetAssociation(request.associationId(), actor);
        String status = actor.isSystemAdmin() || actor.isAssociationReviewer()
                ? normalizeStatus(request.status(), "ACTIVE")
                : "PENDING_REVIEW";
        MemberProfile member = createInternal(UUID.randomUUID(), associationId, request, status);
        auditTrail.record(actor, "MEMBER_CREATE", "ENTERPRISE", member.id().toString(),
                member.associationId(), member.id(), auditDetails(null, member));
        return member;
    }

    public synchronized MemberProfile create(MemberUpsertRequest request) {
        UUID associationId = request.associationId() == null
                ? repository.defaultAssociationId()
                : request.associationId();
        ActorScope internalActor = new ActorScope(
                null, SYSTEM_ACTOR.subject(), SYSTEM_ACTOR.username(), associationId, null,
                SYSTEM_ACTOR.roles(), SYSTEM_ACTOR.partnerAssociationIds());
        return create(request, internalActor);
    }

    @Transactional
    public synchronized MemberProfile createImported(
            MemberUpsertRequest request, UUID associationId, ActorScope actor) {
        UUID scopedAssociationId = targetAssociation(associationId, actor);
        if (request.associationId() != null && !request.associationId().equals(scopedAssociationId)) {
            throw scopeDenied();
        }
        MemberProfile member = createInternal(
                UUID.randomUUID(), scopedAssociationId, request, "PENDING_REVIEW");
        auditTrail.record(actor, "MEMBER_IMPORT_CREATE", "ENTERPRISE", member.id().toString(),
                member.associationId(), member.id(), auditDetails(null, member));
        return member;
    }

    @Transactional
    public synchronized MemberProfile update(
            UUID id, long expectedVersion, MemberUpsertRequest request, ActorScope actor) {
        MemberProfile existing = getUnscoped(id);
        if (!MemberAccessPolicy.canUpdate(actor, existing)) {
            throw scopeDenied();
        }
        ensureNotDeleted(existing);
        ensureVersion(existing, expectedVersion);
        ensureUnique(existing.associationId(), request.name(), normalizeCreditCode(request.unifiedSocialCreditCode()), id);
        if (existing.version() == Long.MAX_VALUE) {
            throw new ConflictException("member version is exhausted");
        }
        boolean requiresReview = actor.isEnterpriseAdmin() || actor.hasRole("ASSOCIATION_OPERATOR");
        String status = requiresReview
                ? "PENDING_REVIEW"
                : normalizeStatus(request.status(), existing.status());
        String visibility = actor.isEnterpriseAdmin()
                ? existing.visibility()
                : normalizeVisibility(request.visibility(), existing.visibility());
        MemberProfile updated = fromRequest(
                id, existing.associationId(), request, visibility, status,
                existing.version() + 1, existing.createdAt(), Instant.now());
        if (!repository.update(updated, expectedVersion)) {
            throw versionMismatch();
        }
        auditTrail.record(actor, "MEMBER_UPDATE", "ENTERPRISE", id.toString(),
                updated.associationId(), id, auditDetails(existing, updated));
        return updated;
    }

    public synchronized MemberProfile update(UUID id, long expectedVersion, MemberUpsertRequest request) {
        return update(id, expectedVersion, request, internalActorFor(getUnscoped(id)));
    }

    @Transactional
    public synchronized MemberProfile review(
            UUID id, long expectedVersion, MemberReviewRequest request, ActorScope actor) {
        MemberProfile existing = getUnscoped(id);
        if (!MemberAccessPolicy.canReview(actor, existing)) {
            throw scopeDenied();
        }
        ensureNotDeleted(existing);
        ensureVersion(existing, expectedVersion);
        MemberProfile reviewed = copyWithStatus(existing, request.decision(), Instant.now());
        if (!repository.update(reviewed, expectedVersion)) {
            throw versionMismatch();
        }
        auditTrail.recordReview(actor, existing.associationId(), id, existing.status(), request.decision(), trimToNull(request.comment()));
        return reviewed;
    }

    @Transactional
    public synchronized MemberProfile delete(UUID id, long expectedVersion, ActorScope actor) {
        MemberProfile existing = getUnscoped(id);
        if (!MemberAccessPolicy.canDelete(actor, existing)) {
            throw scopeDenied();
        }
        ensureVersion(existing, expectedVersion);
        ensureNotDeleted(existing);
        Instant now = Instant.now();
        MemberProfile deleted = new MemberProfile(
                existing.id(), existing.associationId(), existing.name(), existing.unifiedSocialCreditCode(),
                existing.category(), existing.address(), existing.contactName(), existing.contactPhone(),
                existing.introduction(), existing.capabilities(), existing.products(), existing.cooperationNeeds(),
                existing.visibility(), "DELETED", nextVersion(existing), existing.createdAt(), now,
                now, actor.subject(), existing.status());
        if (!repository.update(deleted, expectedVersion)) {
            throw versionMismatch();
        }
        auditTrail.record(actor, "MEMBER_DELETE", "ENTERPRISE", id.toString(),
                existing.associationId(), id, auditDetails(existing, deleted));
        return deleted;
    }

    public synchronized MemberProfile delete(UUID id, long expectedVersion) {
        return delete(id, expectedVersion, internalActorFor(getUnscoped(id)));
    }

    @Transactional
    public synchronized MemberProfile restore(UUID id, long expectedVersion, ActorScope actor) {
        MemberProfile existing = getUnscoped(id);
        if (!MemberAccessPolicy.canDelete(actor, existing)) {
            throw scopeDenied();
        }
        ensureVersion(existing, expectedVersion);
        if (!existing.deleted()) {
            throw new ConflictException("member is not deleted");
        }
        Instant now = Instant.now();
        String restoredStatus = normalizeStatus(existing.statusBeforeDelete(), "DISABLED");
        MemberProfile restored = new MemberProfile(
                existing.id(), existing.associationId(), existing.name(), existing.unifiedSocialCreditCode(),
                existing.category(), existing.address(), existing.contactName(), existing.contactPhone(),
                existing.introduction(), existing.capabilities(), existing.products(), existing.cooperationNeeds(),
                existing.visibility(), restoredStatus, nextVersion(existing), existing.createdAt(), now,
                null, null, null);
        if (!repository.update(restored, expectedVersion)) {
            throw versionMismatch();
        }
        auditTrail.record(actor, "MEMBER_RESTORE", "ENTERPRISE", id.toString(),
                restored.associationId(), id, auditDetails(existing, restored));
        return restored;
    }

    public boolean canEdit(ActorScope actor, MemberProfile member) {
        return !member.deleted() && MemberAccessPolicy.canUpdate(actor, member);
    }

    public boolean canReview(ActorScope actor, MemberProfile member) {
        return !member.deleted() && MemberAccessPolicy.canReview(actor, member);
    }

    private MemberProfile createInternal(
            UUID id, UUID associationId, MemberUpsertRequest request, String status) {
        ensureUnique(associationId, request.name(), normalizeCreditCode(request.unifiedSocialCreditCode()), null);
        Instant now = Instant.now();
        MemberProfile member = fromRequest(
                id, associationId, request, normalizeVisibility(request.visibility(), "MEMBERS"),
                status, 0, now, now);
        repository.insert(member);
        return member;
    }

    private void insertSeed(UUID id, UUID associationId, MemberUpsertRequest request) {
        Instant now = Instant.now();
        repository.insert(fromRequest(id, associationId, request, "MEMBERS", "ACTIVE", 0, now, now));
    }

    private void ensureUnique(UUID associationId, String name, String normalizedCreditCode, UUID ignoredId) {
        String normalizedName = name.trim().toLowerCase(Locale.ROOT);
        boolean duplicate = repository.findAll().stream()
                .filter(member -> !member.id().equals(ignoredId))
                .anyMatch(member -> normalizedCreditCode != null
                        && normalizedCreditCode.equals(member.unifiedSocialCreditCode())
                        || member.associationId().equals(associationId)
                        && normalizedName.equals(member.name().toLowerCase(Locale.ROOT)));
        if (duplicate) {
            throw new ConflictException("member name or unified social credit code already exists");
        }
    }

    private static MemberProfile fromRequest(
            UUID id, UUID associationId, MemberUpsertRequest request, String visibility, String status,
            long version, Instant createdAt, Instant updatedAt) {
        return new MemberProfile(
                id, associationId, request.name().trim(), normalizeCreditCode(request.unifiedSocialCreditCode()),
                request.category().trim(), trimToNull(request.address()), trimToNull(request.contactName()),
                trimToNull(request.contactPhone()), trimToNull(request.introduction()),
                immutable(request.capabilities()), immutable(request.products()), immutable(request.cooperationNeeds()),
                visibility, status, version, createdAt, updatedAt, null, null, null);
    }

    private static MemberProfile copyWithStatus(MemberProfile member, String status, Instant updatedAt) {
        if (member.version() == Long.MAX_VALUE) {
            throw new ConflictException("member version is exhausted");
        }
        return new MemberProfile(
                member.id(), member.associationId(), member.name(), member.unifiedSocialCreditCode(),
                member.category(), member.address(), member.contactName(), member.contactPhone(),
                member.introduction(), member.capabilities(), member.products(), member.cooperationNeeds(),
                member.visibility(), status, member.version() + 1, member.createdAt(), updatedAt,
                member.deletedAt(), member.deletedBySubject(), member.statusBeforeDelete());
    }

    private MemberProfile getUnscoped(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("member", id));
    }

    private static UUID targetAssociation(UUID requestedAssociationId, ActorScope actor) {
        if (actor.isSystemAdmin() && actor.associationId() == null) {
            throw associationContextRequired();
        }
        if (!MemberAccessPolicy.canCreate(actor)
                || actor.associationId() == null
                || requestedAssociationId != null && !requestedAssociationId.equals(actor.associationId())) {
            throw scopeDenied();
        }
        return actor.associationId();
    }

    private static ActorScope internalActorFor(MemberProfile member) {
        return new ActorScope(
                null, SYSTEM_ACTOR.subject(), SYSTEM_ACTOR.username(), member.associationId(), null,
                SYSTEM_ACTOR.roles(), SYSTEM_ACTOR.partnerAssociationIds());
    }

    private static void ensureVersion(MemberProfile existing, long expectedVersion) {
        if (existing.version() != expectedVersion) {
            throw versionMismatch();
        }
    }

    private static void ensureNotDeleted(MemberProfile member) {
        if (member.deleted()) {
            throw new ConflictException("deleted member must be restored before modification");
        }
    }

    private static long nextVersion(MemberProfile member) {
        if (member.version() == Long.MAX_VALUE) {
            throw new ConflictException("member version is exhausted");
        }
        return member.version() + 1;
    }

    private static PreconditionFailedException versionMismatch() {
        return new PreconditionFailedException("member version does not match If-Match");
    }

    private static ForbiddenException scopeDenied() {
        return new ForbiddenException("DATA_SCOPE_DENIED", "member is outside the authenticated data scope");
    }

    private static com.guanxian.platform.shared.error.ApiException associationContextRequired() {
        return new com.guanxian.platform.shared.error.ApiException(
                "ASSOCIATION_CONTEXT_REQUIRED",
                "system administrators must select an association context",
                org.springframework.http.HttpStatus.BAD_REQUEST);
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().toList();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeCreditCode(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static String normalizeStatus(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeVisibility(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String searchableText(MemberProfile member) {
        return String.join(" ", member.name(), nullToEmpty(member.category()), nullToEmpty(member.introduction()),
                String.join(" ", member.capabilities()), String.join(" ", member.products()))
                .toLowerCase(Locale.ROOT);
    }

    private Optional<MemberProfile> authorizeRead(ActorScope actor, MemberProfile member) {
        if (!isCrossAssociationRead(actor, member)) {
            return Optional.of(member);
        }
        Optional<Set<String>> authorization = partnerFieldAuthorization.authorizedFields(
                actor, member.id(), "MEMBER", member.id());
        if (authorization.isEmpty() || !"ACTIVE".equals(member.status())) {
            return Optional.empty();
        }
        Set<String> visibleFields = authorization.orElseThrow().stream()
                .filter(field -> field != null && PartnerFieldAuthorization.MEMBER_FIELDS.contains(field))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!visibleFields.containsAll(PartnerFieldAuthorization.requiredFields("MEMBER"))) {
            return Optional.empty();
        }
        return Optional.of(redactPartnerMember(member, visibleFields));
    }

    private static boolean isCrossAssociationRead(ActorScope actor, MemberProfile member) {
        return !actor.isSystemAdmin()
                && actor.associationId() != null
                && !actor.associationId().equals(member.associationId());
    }

    private static MemberProfile redactPartnerMember(MemberProfile member, Set<String> visibleFields) {
        return new MemberProfile(
                member.id(),
                member.associationId(),
                member.name(),
                null,
                visibleFields.contains("category") ? member.category() : null,
                visibleFields.contains("address") ? member.address() : null,
                null,
                null,
                visibleFields.contains("introduction") ? member.introduction() : null,
                visibleFields.contains("capabilities") ? safeList(member.capabilities()) : List.of(),
                visibleFields.contains("products") ? safeList(member.products()) : List.of(),
                visibleFields.contains("cooperationNeeds") ? safeList(member.cooperationNeeds()) : List.of(),
                member.visibility(),
                member.status(),
                member.version(),
                member.createdAt(),
                member.updatedAt(),
                member.deletedAt(),
                member.deletedBySubject(),
                member.statusBeforeDelete());
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, Object> auditDetails(MemberProfile before, MemberProfile after) {
        MemberProfile value = after == null ? before : after;
        return Map.of(
                "name", value.name(),
                "previousVersion", before == null ? -1 : before.version(),
                "newVersion", after == null ? -1 : after.version(),
                "previousStatus", before == null ? "" : before.status(),
                "newStatus", after == null ? "" : after.status());
    }

    private static final class NoopAuditTrail implements AuditTrail {
        @Override
        public void record(ActorScope actor, String action, String resourceType, String resourceId,
                           UUID associationId, UUID enterpriseId, Map<String, Object> details) {
        }

        @Override
        public void recordReview(
                ActorScope actor, UUID associationId, UUID enterpriseId, String previousStatus, String decision, String comment) {
        }

        @Override
        public List<AuditRecord> findVisible(ActorScope actor, UUID enterpriseId, int limit) {
            return List.of();
        }
    }
}
