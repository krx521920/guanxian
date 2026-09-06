package com.guanxian.platform.tender;

import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class TenderService {
    private static final Set<String> STATUSES = Set.of("ACTIVE", "CLOSED");

    private final TenderStore store;
    private final KeywordSource keywordSource;

    public TenderService(TenderStore store, KeywordSource keywordSource) {
        this.store = store;
        this.keywordSource = keywordSource;
    }

    @Transactional(readOnly = true)
    public TenderPage page(
            ActorScope actor, String query, String category, String region, String status, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String safeCategory = blank(category);
        String safeRegion = blank(region);
        String safeStatus = blank(status);
        return new TenderPage(
                store.findAll(query, safeCategory, safeRegion, safeStatus, safePage * safeSize, safeSize),
                safePage,
                safeSize,
                store.countAll(query, safeCategory, safeRegion, safeStatus));
    }

    @Transactional
    public TenderView create(TenderUpsertRequest request, ActorScope actor) {
        validate(request);
        return store.insert(writableAssociation(actor), request, actor);
    }

    @Transactional
    public List<TenderView> createBatch(List<TenderUpsertRequest> requests, ActorScope actor) {
        UUID associationId = writableAssociation(actor);
        List<TenderView> created = new ArrayList<>();
        for (TenderUpsertRequest request : requests) {
            validate(request);
            created.add(store.insert(associationId, request, actor));
        }
        return created;
    }

    @Transactional(readOnly = true)
    public TenderPage mine(ActorScope actor, int page, int size) {
        UUID enterpriseId = actor.enterpriseId();
        if (enterpriseId == null) {
            throw new ForbiddenException("TENDER_ENTERPRISE_SCOPE_REQUIRED",
                    "an enterprise identity is required to list relevant tenders");
        }
        List<String> keywords = keywordSource.keywordsFor(enterpriseId, actor);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return new TenderPage(
                store.findRelevant(keywords, null, safePage * safeSize, safeSize),
                safePage,
                safeSize,
                store.countRelevant(keywords, null));
    }

    @Transactional
    public List<TenderPushView> push(UUID id, TenderPushRequest request, ActorScope actor) {
        TenderView tender = get(id, actor);
        if (!"ACTIVE".equals(tender.status())) {
            throw new PreconditionFailedException("only ACTIVE tenders can be pushed");
        }
        requirePusher(actor, tender);
        List<UUID> targetIds = request.enterpriseIds() == null || request.enterpriseIds().isEmpty()
                ? keywordSource.allEnterpriseIds(actor)
                : request.enterpriseIds().stream().distinct().toList();
        List<TenderPushView> pushed = new ArrayList<>();
        for (UUID enterpriseId : targetIds) {
            pushed.add(store.upsertPush(
                    id, tender.title(), enterpriseId, keywordSource.enterpriseName(enterpriseId, actor), actor));
        }
        return pushed;
    }

    @Transactional(readOnly = true)
    public List<TenderPushView> pushes(UUID id, ActorScope actor) {
        get(id, actor);
        return store.findPushes(id);
    }

    @Transactional(readOnly = true)
    public TenderView get(UUID id, ActorScope actor) {
        return store.findById(id).orElseThrow(() -> new NotFoundException("tender", id));
    }

    private static void validate(TenderUpsertRequest request) {
        if (request.publishDate() != null && request.deadline() != null
                && request.deadline().isBefore(request.publishDate())) {
            throw new PreconditionFailedException("deadline must not be before publishDate");
        }
        String status = request.status() == null || request.status().isBlank()
                ? "ACTIVE" : request.status().trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new PreconditionFailedException("status must be ACTIVE or CLOSED");
        }
    }

    private static UUID writableAssociation(ActorScope actor) {
        if (actor.associationId() != null && (actor.isSystemAdmin() || actor.isAssociationStaff())) {
            return actor.associationId();
        }
        throw new ForbiddenException("TENDER_WRITE_SCOPE_REQUIRED",
                "an association staff identity is required to maintain tenders");
    }

    private static void requirePusher(ActorScope actor, TenderView tender) {
        if (actor.isSystemAdmin()) {
            return;
        }
        if (actor.isAssociationStaff() && actor.associationId() != null
                && actor.associationId().equals(tender.associationId())) {
            return;
        }
        throw new ForbiddenException("TENDER_SCOPE_VIOLATION",
                "association staff can only push tenders for their own association");
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
