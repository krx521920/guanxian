package com.guanxian.platform.member.internal;

import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.web.MemberImportCommitResult;
import com.guanxian.platform.member.web.MemberImportPreview;
import com.guanxian.platform.member.web.MemberImportRowView;
import com.guanxian.platform.member.web.MemberUpsertRequest;
import com.guanxian.platform.shared.error.ConflictException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.security.ActorScope;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MemberImportService {
    private final MemberWorkbookService workbookService;
    private final MemberImportBatchRepository batchRepository;
    private final MemberRepository memberRepository;
    private final MemberService memberService;
    private final AuditTrail auditTrail;
    private final Validator validator;

    MemberImportService(
            MemberWorkbookService workbookService,
            MemberImportBatchRepository batchRepository,
            MemberRepository memberRepository,
            MemberService memberService,
            AuditTrail auditTrail,
            Validator validator) {
        this.workbookService = workbookService;
        this.batchRepository = batchRepository;
        this.memberRepository = memberRepository;
        this.memberService = memberService;
        this.auditTrail = auditTrail;
        this.validator = validator;
    }

    public byte[] template() {
        return workbookService.createTemplate();
    }

    @Transactional
    public MemberImportPreview preview(
            String originalFilename, byte[] bytes, UUID requestedAssociationId, ActorScope actor) {
        UUID associationId = targetAssociation(requestedAssociationId, actor);
        List<MemberWorkbookService.ParsedRow> parsedRows = workbookService.parse(bytes);
        List<MemberProfile> existing = memberRepository.findAll();
        Set<String> workbookNames = new HashSet<>();
        Set<String> workbookCredits = new HashSet<>();
        List<MemberImportRow> rows = new ArrayList<>();
        for (MemberWorkbookService.ParsedRow parsed : parsedRows) {
            MemberUpsertRequest request = parsed.data();
            List<String> errors = new ArrayList<>(parsed.errors());
            validator.validate(request).stream()
                    .sorted(java.util.Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                    .map(MemberImportService::violationMessage)
                    .forEach(errors::add);
            String normalizedName = normalize(request.name());
            String normalizedCredit = normalize(request.unifiedSocialCreditCode());
            if (!normalizedName.isEmpty() && !workbookNames.add(normalizedName)) {
                errors.add("企业名称在本文件中重复");
            }
            if (!normalizedCredit.isEmpty() && !workbookCredits.add(normalizedCredit)) {
                errors.add("统一社会信用代码在本文件中重复");
            }
            if (!normalizedName.isEmpty() && existing.stream().anyMatch(member ->
                    member.associationId().equals(associationId) && normalize(member.name()).equals(normalizedName))) {
                errors.add("企业名称已存在于当前协会");
            }
            if (!normalizedCredit.isEmpty() && existing.stream().anyMatch(member ->
                    normalize(member.unifiedSocialCreditCode()).equals(normalizedCredit))) {
                errors.add("统一社会信用代码已存在");
            }
            List<String> distinctErrors = errors.stream().distinct().toList();
            rows.add(new MemberImportRow(
                    parsed.rowNumber(), request, distinctErrors,
                    distinctErrors.isEmpty() ? "VALID" : "INVALID", null));
        }
        MemberImportBatch batch = new MemberImportBatch(
                UUID.randomUUID(), associationId, safeFilename(originalFilename), "PREVIEWED",
                actor.subject(), Instant.now(), null, rows);
        batchRepository.save(batch);
        auditTrail.record(actor, "MEMBER_IMPORT_PREVIEW", "MEMBER_IMPORT_BATCH", batch.id().toString(),
                associationId, null, Map.of(
                        "filename", batch.originalFilename(),
                        "totalRows", rows.size(),
                        "validRows", batch.validRows(),
                        "invalidRows", batch.invalidRows()));
        return view(batch);
    }

    public MemberImportPreview get(UUID batchId, ActorScope actor) {
        MemberImportBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NotFoundException("memberImportBatch", batchId));
        ensureBatchScope(batch, actor);
        return view(batch);
    }

    @Transactional
    public synchronized MemberImportCommitResult commit(UUID batchId, ActorScope actor) {
        MemberImportBatch batch = batchRepository.findByIdForCommit(batchId)
                .orElseThrow(() -> new NotFoundException("memberImportBatch", batchId));
        ensureBatchScope(batch, actor);
        if (!"PREVIEWED".equals(batch.status())) {
            throw new ConflictException("member import batch has already been committed or cancelled");
        }
        List<MemberImportRow> validRows = batch.rows().stream()
                .filter(row -> "VALID".equals(row.status())).toList();
        if (validRows.isEmpty()) {
            throw new ConflictException("member import batch has no valid rows");
        }
        revalidateAgainstCurrentData(batch.associationId(), validRows);
        Map<Integer, UUID> imported = new LinkedHashMap<>();
        for (MemberImportRow row : validRows) {
            MemberProfile member = memberService.createImported(row.data(), batch.associationId(), actor);
            imported.put(row.rowNumber(), member.id());
        }
        if (!batchRepository.markCommitted(batch.id(), imported)) {
            throw new ConflictException("member import batch state changed concurrently");
        }
        auditTrail.record(actor, "MEMBER_IMPORT_COMMIT", "MEMBER_IMPORT_BATCH", batch.id().toString(),
                batch.associationId(), null, Map.of(
                        "importedRows", imported.size(),
                        "invalidRows", batch.invalidRows()));
        return new MemberImportCommitResult(
                batch.id(), imported.size(), batch.invalidRows(), List.copyOf(imported.values()));
    }

    private void revalidateAgainstCurrentData(UUID associationId, List<MemberImportRow> rows) {
        List<MemberProfile> existing = memberRepository.findAll();
        for (MemberImportRow row : rows) {
            String name = normalize(row.data().name());
            String credit = normalize(row.data().unifiedSocialCreditCode());
            boolean duplicate = existing.stream().anyMatch(member ->
                    member.associationId().equals(associationId) && normalize(member.name()).equals(name)
                            || !credit.isEmpty() && normalize(member.unifiedSocialCreditCode()).equals(credit));
            if (duplicate) {
                throw new ConflictException("member data changed after preview; generate a new preview");
            }
        }
    }

    private UUID targetAssociation(UUID requestedAssociationId, ActorScope actor) {
        if (!MemberAccessPolicy.canCreate(actor)) {
            throw scopeDenied();
        }
        if (actor.isSystemAdmin()) {
            UUID selected = requestedAssociationId != null
                    ? requestedAssociationId : actor.associationId();
            if (selected == null) {
                throw new com.guanxian.platform.shared.error.ApiException(
                        "ASSOCIATION_CONTEXT_REQUIRED",
                        "system administrators must select an association context",
                        org.springframework.http.HttpStatus.BAD_REQUEST);
            }
            return selected;
        }
        if (actor.associationId() == null
                || requestedAssociationId != null && !actor.associationId().equals(requestedAssociationId)) {
            throw scopeDenied();
        }
        return actor.associationId();
    }

    private static void ensureBatchScope(MemberImportBatch batch, ActorScope actor) {
        if (!actor.isSystemAdmin()
                && (!actor.isAssociationStaff() || !batch.associationId().equals(actor.associationId()))) {
            throw scopeDenied();
        }
    }

    private static MemberImportPreview view(MemberImportBatch batch) {
        return new MemberImportPreview(
                batch.id(), batch.originalFilename(), batch.status(), batch.rows().size(),
                batch.validRows(), batch.invalidRows(), batch.createdAt(), batch.rows().stream()
                .map(row -> new MemberImportRowView(
                        row.rowNumber(), row.data(), row.errors(), row.status(), row.enterpriseId()))
                .toList());
    }

    private static String violationMessage(ConstraintViolation<MemberUpsertRequest> violation) {
        return violation.getPropertyPath() + "：" + violation.getMessage();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "member-survey.xlsx";
        }
        String normalized = filename.replace('\\', '/');
        String leaf = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        return leaf.isEmpty() ? "member-survey.xlsx" : leaf.substring(0, Math.min(leaf.length(), 255));
    }

    private static ForbiddenException scopeDenied() {
        return new ForbiddenException("DATA_SCOPE_DENIED", "member import is outside the authenticated data scope");
    }
}
