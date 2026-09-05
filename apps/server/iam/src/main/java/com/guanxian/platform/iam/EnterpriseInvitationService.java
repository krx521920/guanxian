package com.guanxian.platform.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.error.*;
import com.guanxian.platform.shared.security.ActorScope;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static com.guanxian.platform.iam.EnterpriseInvitations.*;

@Service
@ConditionalOnProperty(name = "guanxian.security.mode", havingValue = "jwt", matchIfMissing = true)
class EnterpriseInvitationService {
    private static final String SELECT = """
            SELECT i.*, e.name AS enterprise_name, a.name AS association_name
              FROM enterprise_owner_invitation i
              JOIN enterprise e ON e.id=i.enterprise_id
              JOIN association a ON a.id=i.association_id
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    EnterpriseInvitationService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, Clock.systemUTC());
    }
    EnterpriseInvitationService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc; this.mapper = mapper; this.clock = clock;
    }

    Identity identity(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) throw denied();
        String subject = jwt.getToken().getSubject();
        String username = jwt.getToken().getClaimAsString("preferred_username");
        if (subject == null || subject.isBlank() || subject.length() > 200 || username == null
                || username.isBlank() || username.length() > 100) throw denied();
        if (count("SELECT COUNT(*) FROM revoked_identity_subject WHERE external_subject=:subject", params("subject", subject)) > 0
                || count("SELECT COUNT(*) FROM user_account WHERE external_subject=:subject AND status<>'ACTIVE'", params("subject", subject)) > 0) throw denied();
        String name = jwt.getToken().getClaimAsString("name");
        return new Identity(subject, username.trim(), name == null || name.isBlank() ? username.trim() : name.substring(0, Math.min(100, name.length())));
    }

    Page list(ActorScope actor, int page, int size) {
        requireAdmin(actor);
        int safePage = Math.max(0, Math.min(page, 100000)), safeSize = Math.max(1, Math.min(size, 100));
        var p = scope(actor).addValue("limit", safeSize).addValue("offset", (long) safePage * safeSize);
        String condition = " WHERE i.association_id=:associationId" + (actor.enterpriseId() == null ? "" : " AND i.enterprise_id=:enterpriseId");
        long total = count("SELECT COUNT(*) FROM enterprise_owner_invitation i" + condition, p);
        return new Page(jdbc.query(SELECT + condition + " ORDER BY i.created_at DESC, i.id LIMIT :limit OFFSET :offset", p,
                (rs, row) -> view(rs, true)), total, safePage, safeSize);
    }

    List<View> mine(Authentication authentication) {
        Identity self = identity(authentication);
        return jdbc.query(SELECT + " WHERE i.claim_subject=:subject ORDER BY i.created_at DESC, i.id LIMIT 30",
                params("subject", self.subject()), (rs, row) -> view(rs, false));
    }

    @Transactional
    Issued create(Create request, ActorScope actor) {
        requireAdmin(actor);
        requireEnterprise(request.enterpriseId(), actor.associationId(), actor.enterpriseId(), true);
        String username = normalizedUsername(request.username());
        var p = scope(actor).addValue("enterpriseId", request.enterpriseId()).addValue("username", username)
                .addValue("now", time(clock.instant()));
        if (count("""
                SELECT COUNT(*) FROM enterprise_owner_invitation
                 WHERE enterprise_id=:enterpriseId AND invited_username=:username
                   AND status IN ('ISSUED', 'CLAIMED') AND expires_at>:now
                """, p) > 0) throw new ConflictException("该企业和账号已有有效邀请，请先撤销或处理原邀请");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID id = UUID.randomUUID();
        p.addValue("id", id).addValue("hash", hash(token)).addValue("actor", actor.subject())
                .addValue("expires", time(clock.instant().plus(Duration.ofHours(72))));
        jdbc.update("""
                INSERT INTO enterprise_owner_invitation
                  (id, association_id, enterprise_id, invited_username, token_hash, status, created_by_subject, created_at, expires_at)
                VALUES (:id, :associationId, :enterpriseId, :username, :hash, 'ISSUED', :actor, :now, :expires)
                """, p);
        View value = byId(id, true);
        audit(actor.subject(), actor.username(), actor.userId(), actor.associationId(), value,
                "ENTERPRISE_INVITATION_CREATE", Map.of("invitedUsername", username));
        return new Issued(value, token);
    }

    View preview(String token, Authentication authentication) {
        Identity self = identity(authentication);
        View value = byToken(token, false);
        requireRecipient(value, self);
        requireUsable(value);
        requireEnterprise(value.enterpriseId(), invitationAssociation(value.id()), null, false);
        return value;
    }

    @Transactional
    View claim(Claim request, Authentication authentication) {
        if (!request.confirmed()) throw invalid("请先确认您获授权代表该企业提交绑定申请");
        Identity self = identity(authentication);
        if (ActorScopes.roles(authentication).stream().anyMatch(role -> Set.of("SYSTEM_ADMIN", "ASSOCIATION_ADMIN", "ASSOCIATION_OPERATOR").contains(role))) throw denied();
        View initial = byToken(request.token(), false);
        UUID associationId = invitationAssociation(initial.id());
        requireEnterprise(initial.enterpriseId(), associationId, null, true);
        lock(initial.id());
        View value = byId(initial.id(), false);
        requireRecipient(value, self);
        requireUsable(value);
        requireAccountCompatible(self, associationId, value.enterpriseId(), false);
        if ("CLAIMED".equals(value.status()) && self.subject().equals(claimSubject(value.id()))) return value;
        if (!"ISSUED".equals(value.status())) throw new ConflictException("该邀请已经被确认或关闭");
        if (self.subject().equals(jdbc.queryForObject("SELECT created_by_subject FROM enterprise_owner_invitation WHERE id=:id", params("id", value.id()), String.class))) throw denied();
        jdbc.update("""
                UPDATE enterprise_owner_invitation
                   SET status='CLAIMED', version=version+1, claim_subject=:subject, claim_username=:username,
                       claim_display_name=:displayName, claimed_at=:now
                 WHERE id=:id
                """, params("id", value.id()).addValue("subject", self.subject()).addValue("username", self.username())
                .addValue("displayName", self.displayName()).addValue("now", time(clock.instant())));
        View claimed = byId(value.id(), false);
        audit(self.subject(), self.username(), null, associationId, claimed, "ENTERPRISE_INVITATION_CLAIM", Map.of());
        return claimed;
    }

    @Transactional
    View review(UUID id, long version, Review request, ActorScope actor) {
        requireAdmin(actor);
        if (!actor.isSystemAdmin()) throw denied();
        View value = scopedLocked(id, actor);
        requireVersion(value, version);
        if (!"CLAIMED".equals(value.status())) throw new ConflictException("只能审核尚未处理且未过期的负责人确认申请");
        String subject = claimSubject(id);
        if (actor.subject().equals(subject)) throw denied();
        if (request.note() == null || request.note().isBlank() || request.note().length() > 1000) throw invalid("必须填写核验或退回说明");
        String status;
        UUID accountId = null;
        if ("APPROVE".equals(request.decision())) {
            requireUsable(value);
            var identity = new Identity(subject, jdbc.queryForObject("SELECT claim_username FROM enterprise_owner_invitation WHERE id=:id", params("id", id), String.class), value.claimantName());
            Account existing = requireAccountCompatible(identity, actor.associationId(), value.enterpriseId(), true);
            accountId = existing == null ? UUID.randomUUID() : existing.id();
            long nextVersion = existing == null ? 0 : existing.version() + 1;
            var p = scope(actor).addValue("id", accountId).addValue("enterpriseId", value.enterpriseId())
                    .addValue("subject", identity.subject()).addValue("username", identity.username())
                    .addValue("displayName", identity.displayName()).addValue("version", nextVersion)
                    .addValue("invitationId", id).addValue("now", time(clock.instant()));
            try {
                if (existing == null) {
                    jdbc.update("""
                            INSERT INTO user_account (id, association_id, enterprise_id, external_subject, username, display_name, status, version, created_at, updated_at)
                            VALUES (:id, :associationId, :enterpriseId, :subject, :username, :displayName, 'ACTIVE', 0, :now, :now)
                            """, p);
                } else {
                    jdbc.update("UPDATE user_account SET enterprise_id=:enterpriseId, association_id=:associationId, version=:version, updated_at=:now WHERE id=:id", p);
                }
                jdbc.update("DELETE FROM enterprise_owner_grant WHERE account_id=:id", p);
                jdbc.update("""
                        INSERT INTO enterprise_owner_grant (account_id, invitation_id, external_subject, association_id, enterprise_id, binding_version, role_code, granted_at)
                        VALUES (:id, :invitationId, :subject, :associationId, :enterpriseId, :version, 'ENTERPRISE_ADMIN', :now)
                        """, p);
            } catch (DataIntegrityViolationException exception) {
                throw new ConflictException("该统一账号已被绑定，请重新核对，不能覆盖已有归属");
            }
            status = "APPROVED";
        } else if ("REJECT".equals(request.decision())) status = "REJECTED";
        else throw invalid("无效的审核决定");
        jdbc.update("""
                UPDATE enterprise_owner_invitation
                   SET status=:status, version=version+1, reviewed_by_subject=:actor, reviewed_at=:now,
                       review_note=:note, account_id=:accountId WHERE id=:id
                """, params("id", id).addValue("status", status).addValue("actor", actor.subject())
                .addValue("now", time(clock.instant())).addValue("note", request.note().trim()).addValue("accountId", accountId));
        View reviewed = byId(id, true);
        audit(actor.subject(), actor.username(), actor.userId(), actor.associationId(), reviewed,
                "ENTERPRISE_INVITATION_" + status, Map.of("reviewNote", request.note().trim(), "claimSubject", subject));
        return reviewed;
    }

    @Transactional
    View revoke(UUID id, long version, ActorScope actor) {
        requireAdmin(actor);
        View value = scopedLocked(id, actor);
        requireVersion(value, version);
        if (Set.of("APPROVED", "REJECTED", "REVOKED").contains(value.status())) throw new ConflictException("邀请已关闭；已开通账号请使用账号停用/解绑功能");
        jdbc.update("UPDATE enterprise_owner_invitation SET status='REVOKED', version=version+1, reviewed_by_subject=:actor, reviewed_at=:now WHERE id=:id",
                params("id", id).addValue("actor", actor.subject()).addValue("now", time(clock.instant())));
        View revoked = byId(id, true);
        audit(actor.subject(), actor.username(), actor.userId(), actor.associationId(), revoked, "ENTERPRISE_INVITATION_REVOKE", Map.of());
        return revoked;
    }

    private Account requireAccountCompatible(Identity self, UUID associationId, UUID enterpriseId, boolean lock) {
        if (count("SELECT COUNT(*) FROM revoked_identity_subject WHERE external_subject=:subject", params("subject", self.subject())) > 0) throw denied();
        var matches = jdbc.query("""
                SELECT id, external_subject, username, association_id, enterprise_id, status, version FROM user_account
                 WHERE external_subject=:subject OR lower(username)=:username
                """ + (lock ? " FOR UPDATE" : ""), params("subject", self.subject()).addValue("username", normalizedUsername(self.username())),
                (rs, row) -> new Account(rs.getObject("id", UUID.class), rs.getString("external_subject"),
                        rs.getObject("association_id", UUID.class), rs.getObject("enterprise_id", UUID.class), rs.getString("status"), rs.getLong("version")));
        if (matches.isEmpty()) return null;
        if (matches.size() != 1) throw new ConflictException("账号名称与统一身份存在冲突，请联系系统管理员");
        Account account = matches.getFirst();
        if (!self.subject().equals(account.subject()) || !"ACTIVE".equals(account.status())
                || account.version() == Long.MAX_VALUE
                || account.associationId() != null && !associationId.equals(account.associationId())
                || account.enterpriseId() != null && !enterpriseId.equals(account.enterpriseId())) {
            throw new ConflictException("账号已绑定其他主体、被停用或需要人工处理，不能通过邀请覆盖");
        }
        return account;
    }

    private View scopedLocked(UUID id, ActorScope actor) {
        View initial = byId(id, true);
        UUID associationId = invitationAssociation(id);
        if (!actor.associationId().equals(associationId)
                || actor.enterpriseId() != null && !actor.enterpriseId().equals(initial.enterpriseId())) throw denied();
        requireEnterprise(initial.enterpriseId(), associationId, actor.enterpriseId(), true);
        lock(id);
        return byId(id, true);
    }
    private void lock(UUID id) { jdbc.queryForObject("SELECT id FROM enterprise_owner_invitation WHERE id=:id FOR UPDATE", params("id", id), UUID.class); }
    private UUID invitationAssociation(UUID id) { return jdbc.queryForObject("SELECT association_id FROM enterprise_owner_invitation WHERE id=:id", params("id", id), UUID.class); }
    private String claimSubject(UUID id) { return jdbc.queryForObject("SELECT claim_subject FROM enterprise_owner_invitation WHERE id=:id", params("id", id), String.class); }
    private View byId(UUID id, boolean admin) {
        return jdbc.query(SELECT + " WHERE i.id=:id", params("id", id), (rs, row) -> view(rs, admin)).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("invitation", id));
    }
    private View byToken(String token, boolean admin) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw unavailable();
        return jdbc.query(SELECT + " WHERE i.token_hash=:hash", params("hash", hash(token)), (rs, row) -> view(rs, admin))
                .stream().findFirst().orElseThrow(EnterpriseInvitationService::unavailable);
    }
    private View view(ResultSet rs, boolean admin) throws SQLException {
        Instant expires = rs.getTimestamp("expires_at").toInstant();
        String status = rs.getString("status");
        if (Set.of("ISSUED", "CLAIMED").contains(status) && !expires.isAfter(clock.instant())) status = "EXPIRED";
        return new View(rs.getObject("id", UUID.class), rs.getObject("enterprise_id", UUID.class), rs.getString("enterprise_name"),
                rs.getString("association_name"), rs.getString("invited_username"), status, rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), expires, rs.getString("claim_display_name"),
                admin ? rs.getString("claim_subject") : null, rs.getTimestamp("claimed_at") == null ? null : rs.getTimestamp("claimed_at").toInstant(),
                rs.getString("review_note"), rs.getObject("account_id", UUID.class));
    }
    private void requireEnterprise(UUID enterpriseId, UUID associationId, UUID selectedEnterprise, boolean lock) {
        if (selectedEnterprise != null && !selectedEnterprise.equals(enterpriseId)) throw denied();
        var ids = jdbc.queryForList("""
                SELECT e.id FROM enterprise e JOIN association a ON a.id=e.association_id
                 WHERE e.id=:enterpriseId AND e.association_id=:associationId AND a.status='ACTIVE'
                   AND e.deleted_at IS NULL AND e.status NOT IN ('DISABLED', 'DELETED')
                """ + (lock ? " FOR UPDATE" : ""), params("enterpriseId", enterpriseId).addValue("associationId", associationId), UUID.class);
        if (ids.size() != 1) throw denied();
    }
    private void audit(String subject, String username, UUID userId, UUID associationId, View value, String action, Map<String, Object> details) {
        try {
            jdbc.update("""
                    INSERT INTO audit_log (actor_user_id, actor_subject, actor_username, association_id, enterprise_id,
                      action, resource_type, resource_id, resource_version, outcome, details, request_id)
                    VALUES ((SELECT id FROM user_account WHERE id=:userId), :subject, :username, :associationId, :enterpriseId,
                      :action, 'ENTERPRISE_INVITATION', :id, :version, 'SUCCESS', CAST(:details AS jsonb), :requestId)
                    """, params("userId", userId).addValue("subject", subject).addValue("username", username)
                    .addValue("associationId", associationId).addValue("enterpriseId", value.enterpriseId())
                    .addValue("action", action).addValue("id", value.id().toString()).addValue("version", value.version())
                    .addValue("details", mapper.writeValueAsString(details)).addValue("requestId", Objects.requireNonNullElse(MDC.get("requestId"), "internal")));
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Invitation audit failed", exception); }
    }
    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static void requireAdmin(ActorScope actor) {
        if (actor == null || (!actor.isSystemAdmin() && !actor.isAssociationReviewer()) || actor.associationId() == null) throw denied();
    }
    private void requireRecipient(View value, Identity self) {
        String claimedSubject = claimSubject(value.id());
        if (!value.username().equals(normalizedUsername(self.username()))
                || claimedSubject != null && !self.subject().equals(claimedSubject)) throw unavailable();
    }
    private static void requireUsable(View value) { if (!Set.of("ISSUED", "CLAIMED").contains(value.status())) throw unavailable(); }
    private static void requireVersion(View value, long version) { if (value.version() != version) throw new PreconditionFailedException("邀请已更新，请刷新后重试"); }
    private static String normalizedUsername(String username) {
        if (username == null || username.isBlank() || username.length() > 100 || username.chars().anyMatch(Character::isISOControl)) throw invalid("请填写统一认证账号名");
        return username.trim().toLowerCase(Locale.ROOT);
    }
    private static OffsetDateTime time(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
    private static MapSqlParameterSource params(String key, Object value) { return new MapSqlParameterSource(key, value); }
    private static MapSqlParameterSource scope(ActorScope actor) { return params("associationId", actor.associationId()).addValue("enterpriseId", actor.enterpriseId()); }
    private long count(String sql, MapSqlParameterSource p) { return Objects.requireNonNullElse(jdbc.queryForObject(sql, p, Long.class), 0L); }
    private static ApiException invalid(String message) { return new ApiException("INVALID_ENTERPRISE_INVITATION", message, HttpStatus.BAD_REQUEST); }
    private static ApiException unavailable() { return new ApiException("ENTERPRISE_INVITATION_UNAVAILABLE", "邀请不可用、已过期或与当前账号不符", HttpStatus.NOT_FOUND); }
    private static ForbiddenException denied() { return new ForbiddenException("ENTERPRISE_INVITATION_FORBIDDEN", "当前账号或所选范围不能执行该操作，请联系系统管理员核验"); }
    private record Account(UUID id, String subject, UUID associationId, UUID enterpriseId, String status, long version) { }
}
