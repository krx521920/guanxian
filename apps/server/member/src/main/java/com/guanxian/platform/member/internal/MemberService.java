package com.guanxian.platform.member.internal;

import com.guanxian.platform.member.api.MemberDirectory;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.web.MemberUpsertRequest;
import com.guanxian.platform.shared.error.ConflictException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class MemberService implements MemberDirectory {
    private final MemberRepository repository;
    @Value("${guanxian.member.seed-demo-data:false}")
    private boolean seedDemoData;

    MemberService(MemberRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void seed() {
        if (!seedDemoData || !repository.findAll().isEmpty()) {
            return;
        }
        create(new MemberUpsertRequest(
                "京城管网科技有限公司", "91110000DEMO00001", "智慧管网",
                "北京市海淀区", "张工", "13800000001", "提供地下管线监测与数字化平台服务",
                List.of("管线监测", "泄漏预警", "数字孪生"),
                List.of("智能监测终端", "管网数字孪生平台"),
                List.of("寻找燃气及供热场景合作方"), "ACTIVE"));
        create(new MemberUpsertRequest(
                "北方阀门制造有限公司", "91110000DEMO00002", "装备制造",
                "北京市大兴区", "李经理", "13800000002", "生产供水、燃气和热力管网阀门",
                List.of("阀门制造", "带压维护"),
                List.of("燃气球阀", "供水蝶阀"),
                List.of("对接管线施工及运营单位"), "ACTIVE"));
    }

    @Override
    public List<MemberProfile> findAll(String query) {
        String keyword = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return repository.findAll().stream()
                .filter(member -> keyword.isEmpty() || searchableText(member).contains(keyword))
                .sorted(Comparator.comparing(MemberProfile::name).thenComparing(MemberProfile::id))
                .toList();
    }

    @Override
    public Optional<MemberProfile> findById(UUID id) {
        return repository.findById(id);
    }

    public MemberProfile get(UUID id) {
        return findById(id).orElseThrow(() -> new NotFoundException("member", id));
    }

    public synchronized MemberProfile create(MemberUpsertRequest request) {
        ensureCreditCodeUnique(normalizeCreditCode(request.unifiedSocialCreditCode()), null);
        Instant now = Instant.now();
        MemberProfile member = fromRequest(UUID.randomUUID(), request, 0, now, now);
        repository.insert(member);
        return member;
    }

    public synchronized MemberProfile update(UUID id, long expectedVersion, MemberUpsertRequest request) {
        MemberProfile existing = get(id);
        ensureVersion(existing, expectedVersion);
        ensureCreditCodeUnique(normalizeCreditCode(request.unifiedSocialCreditCode()), id);
        if (existing.version() == Long.MAX_VALUE) {
            throw new ConflictException("member version is exhausted");
        }
        MemberProfile updated = fromRequest(
                id, request, existing.version() + 1, existing.createdAt(), Instant.now());
        if (!repository.update(updated, expectedVersion)) {
            throw versionMismatch();
        }
        return updated;
    }

    public synchronized MemberProfile delete(UUID id, long expectedVersion) {
        MemberProfile existing = get(id);
        ensureVersion(existing, expectedVersion);
        if (!repository.deleteById(id, expectedVersion)) {
            throw versionMismatch();
        }
        return existing;
    }

    private void ensureCreditCodeUnique(String normalizedCreditCode, UUID ignoredId) {
        if (normalizedCreditCode == null) {
            return;
        }
        boolean duplicate = repository.findAll().stream()
                .anyMatch(member -> !member.id().equals(ignoredId)
                        && normalizedCreditCode.equals(member.unifiedSocialCreditCode()));
        if (duplicate) {
            throw new ConflictException("unified social credit code already exists");
        }
    }

    private static MemberProfile fromRequest(
            UUID id, MemberUpsertRequest request, long version, Instant createdAt, Instant updatedAt) {
        return new MemberProfile(
                id,
                request.name().trim(),
                normalizeCreditCode(request.unifiedSocialCreditCode()),
                request.category().trim(),
                trimToNull(request.address()),
                trimToNull(request.contactName()),
                trimToNull(request.contactPhone()),
                trimToNull(request.introduction()),
                immutable(request.capabilities()),
                immutable(request.products()),
                immutable(request.cooperationNeeds()),
                request.status() == null || request.status().isBlank()
                        ? "ACTIVE" : request.status().trim().toUpperCase(Locale.ROOT),
                version,
                createdAt,
                updatedAt);
    }

    private static void ensureVersion(MemberProfile existing, long expectedVersion) {
        if (existing.version() != expectedVersion) {
            throw versionMismatch();
        }
    }

    private static PreconditionFailedException versionMismatch() {
        return new PreconditionFailedException("member version does not match If-Match");
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeCreditCode(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static String searchableText(MemberProfile member) {
        return String.join(" ",
                member.name(),
                nullToEmpty(member.category()),
                nullToEmpty(member.introduction()),
                String.join(" ", member.capabilities()),
                String.join(" ", member.products())).toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
