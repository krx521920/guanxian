package com.guanxian.platform.member.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.web.MemberUpsertRequest;
import com.guanxian.platform.shared.error.*;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.guanxian.platform.member.internal.ProfileWorkflow.*;

@Service
public class ProfileWorkflowService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MemberRepository members;
    private final AuditTrail audit;

    public ProfileWorkflowService(JdbcTemplate jdbc, ObjectMapper json, MemberRepository members, AuditTrail audit) {
        this.jdbc = jdbc; this.json = json; this.members = members; this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public View get(UUID id, ActorScope actor) {
        MemberProfile member = scoped(id, actor);
        return view(member, read(id), actor);
    }

    public record Pending(UUID id, String name, Instant submittedAt) { }
    public List<Pending> pending(ActorScope actor, int page) {
        require(actor != null && actor.associationId()!=null && (actor.isAssociationStaff() || actor.isSystemAdmin()));
        if(page<0 || page>10000) throw conflict("页码无效");
        return jdbc.query("""
                SELECT e.id,e.name,w.state_json FROM enterprise_profile_workflow w JOIN enterprise e ON e.id=w.enterprise_id
                WHERE w.draft_status='SUBMITTED' AND e.association_id=? AND e.deleted_at IS NULL AND e.status<>'DISABLED'
                  AND (CAST(? AS uuid) IS NULL OR e.id=CAST(? AS uuid))
                ORDER BY e.name,e.id LIMIT 20 OFFSET ?
                """,(rs,n)->new Pending(rs.getObject(1,UUID.class),rs.getString(2),decode(rs.getString(3)).draft().submittedAt()),
                actor.associationId(),actor.enterpriseId()==null?null:actor.enterpriseId().toString(),actor.enterpriseId()==null?null:actor.enterpriseId().toString(),(long)page*20);
    }

    @Transactional
    public View save(UUID id, long version, long baseVersion, MemberUpsertRequest content, ActorScope actor) {
        MemberProfile member = lock(id, actor);
        require(MemberAccessPolicy.canUpdate(actor, member));
        currentBase(member, baseVersion);
        Row row = expected(id, version);
        Draft old = row.state().draft();
        if (old != null && "SUBMITTED".equals(old.status())) throw conflict("审核中的版本已冻结，请等待审核结果");
        // Ordinary revisions cannot alter legal identity, lifecycle or sharing configuration.
        if (!member.name().equals(content.name().trim())
                || !Objects.equals(normalize(member.unifiedSocialCreditCode()), normalize(content.unifiedSocialCreditCode()))
                || content.associationId() != null && !member.associationId().equals(content.associationId()))
            throw new ForbiddenException("ENTERPRISE_IDENTITY_CHANGE_REQUIRES_VERIFICATION", "主体身份变更须由协会单独核验");
        boolean continuing = old != null && Set.of("DRAFT", "REJECTED").contains(old.status());
        Set<String> editors = new HashSet<>(continuing ? old.editors() : Set.of());
        editors.add(actor.subject());
        MemberUpsertRequest safe = new MemberUpsertRequest(member.name(), member.unifiedSocialCreditCode(),
                content.category(), content.address(), content.contactName(), content.contactPhone(), content.contactEmail(),
                content.introduction(), content.capabilities(), content.products(), content.services(),
                content.applicationScenarios(), content.cooperationNeeds(), member.visibility(), member.status(), member.associationId());
        Draft draft = new Draft(continuing ? old.id() : UUID.randomUUID(), member.version(), safe, "DRAFT", Set.copyOf(editors),
                null, continuing ? old.reviewNote() : null, continuing ? old.reviewedBy() : null, null,
                continuing ? old.reviewedAt() : null);
        return store(member, row, new State(draft, row.state().approved(), row.state().publication()), row.published(), actor, "PROFILE_DRAFT_SAVED");
    }

    @Transactional
    public View submit(UUID id, long version, ActorScope actor) {
        MemberProfile member = lock(id, actor);
        require(MemberAccessPolicy.canUpdate(actor, member));
        Row row = expected(id, version); Draft draft = requiredDraft(row);
        if (!"DRAFT".equals(draft.status())) throw conflict("请先保存可编辑草稿后再提交");
        currentBase(member, draft.baseVersion());
        Draft frozen = new Draft(draft.id(), draft.baseVersion(), draft.content(), "SUBMITTED", draft.editors(),
                actor.subject(), draft.reviewNote(), draft.reviewedBy(), Instant.now(), draft.reviewedAt());
        return store(member, row, new State(frozen, row.state().approved(), row.state().publication()), row.published(), actor, "PROFILE_SUBMITTED");
    }

    @Transactional
    public View review(UUID id, long version, boolean approve, String note, ActorScope actor) {
        MemberProfile member = lock(id, actor);
        require(MemberAccessPolicy.canReview(actor, member));
        Row row = expected(id, version); Draft draft = requiredDraft(row);
        if (!"SUBMITTED".equals(draft.status())) throw conflict("只有已提交的冻结版本可以审核");
        require(independent(draft.editors(), draft.submittedBy(), actor));
        String reason = reason(note);
        Instant now = Instant.now();
        Approved approved = row.state().approved();
        if (approve) {
            currentBase(member, draft.baseVersion());
            // Keep lifecycle status unchanged: a normal profile revision is not a membership decision.
            MemberProfile updated = MemberService.fromRequest(id, member.associationId(), draft.content(),
                    member.visibility(), member.status(), Math.addExact(member.version(), 1), member.createdAt(), now);
            if (!members.update(updated, member.version())) throw stale();
            member = updated;
            approved = new Approved(draft.id(), updated, draft.editors(), draft.submittedBy(), actor.subject(), now, null, null, -1);
        }
        Draft reviewed = new Draft(draft.id(), draft.baseVersion(), draft.content(), approve ? "APPROVED" : "REJECTED",
                draft.editors(), draft.submittedBy(), reason, actor.subject(), draft.submittedAt(), now);
        return store(member, row, new State(reviewed, approved, row.state().publication()), row.published(), actor,
                approve ? "PROFILE_APPROVED" : "PROFILE_REJECTED");
    }

    @Transactional
    public View consent(UUID id, long version, ActorScope actor) {
        MemberProfile member = lock(id, actor); require(owner(actor, member));
        Row row = expected(id, version); Approved approved = requiredApproved(row, member);
        Approved consented = new Approved(approved.id(), approved.profile(), approved.editors(), approved.submittedBy(),
                approved.reviewedBy(), approved.approvedAt(), actor.subject(), Instant.now(), row.epoch());
        return store(member, row, new State(row.state().draft(), consented, row.state().publication()), row.published(), actor, "PROFILE_PUBLIC_CONSENT");
    }

    @Transactional
    public View publish(UUID id, long version, ActorScope actor) {
        MemberProfile member = lock(id, actor); require(MemberAccessPolicy.canReview(actor, member));
        Row row = expected(id, version); Approved approved = requiredApproved(row, member);
        require(independent(approved.editors(), approved.submittedBy(), actor));
        if (approved.consentedAt() == null || approved.consentEpoch() != row.epoch()) throw conflict("须由企业负责人确认本审核版本的公开授权");
        PublicProfile snapshot = PublicProfile.from(approved.profile(), UUID.randomUUID(), Instant.now());
        return store(member, row, new State(row.state().draft(), approved, snapshot), true, actor, "PROFILE_PUBLISHED");
    }

    @Transactional
    public View withdraw(UUID id, long version, String note, ActorScope actor) {
        MemberProfile member = lock(id, actor);
        require(owner(actor, member) || MemberAccessPolicy.canReview(actor, member));
        Row row = expected(id, version); reason(note);
        Approved approved = row.state().approved();
        if (approved != null) approved = new Approved(approved.id(), approved.profile(), approved.editors(), approved.submittedBy(),
                approved.reviewedBy(), approved.approvedAt(), null, null, -1);
        View result = store(member, row, new State(row.state().draft(), approved, row.state().publication()), false, actor, "PROFILE_WITHDRAWN");
        audit.record(actor, "PROFILE_WITHDRAWAL_REASON", "ENTERPRISE", id.toString(), member.associationId(), id, Map.of("reason", note.trim()));
        return result;
    }

    public List<PublicProfile> publicPage(String query, int page) {
        String keyword = query == null ? "" : query.trim();
        if (keyword.length() > 100 || page < 0 || page > 10000) throw conflict("查询范围无效");
        // Filtering/paging operate exclusively on published snapshots, never internal member fields.
        String search = "%" + keyword.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return jdbc.query("""
                SELECT w.state_json FROM enterprise_profile_workflow w
                JOIN enterprise e ON e.id=w.enterprise_id JOIN association a ON a.id=e.association_id
                WHERE w.published=TRUE AND e.status='ACTIVE' AND e.deleted_at IS NULL AND a.status='ACTIVE'
                  AND (LOWER(w.public_name) LIKE ? ESCAPE '!' OR LOWER(w.public_category) LIKE ? ESCAPE '!')
                ORDER BY w.public_name,w.enterprise_id LIMIT 20 OFFSET ?
                """, (rs, n) -> decode(rs.getString(1)).publication(),search,search,(long)page * 20);
    }

    public PublicProfile publicDetail(UUID id) {
        return jdbc.query("""
                SELECT w.state_json FROM enterprise_profile_workflow w
                JOIN enterprise e ON e.id=w.enterprise_id JOIN association a ON a.id=e.association_id
                WHERE w.enterprise_id=? AND w.published=TRUE AND e.status='ACTIVE'
                  AND e.deleted_at IS NULL AND a.status='ACTIVE'
                """, (rs,n) -> decode(rs.getString(1)).publication(), id).stream().filter(Objects::nonNull)
                .findFirst().orElseThrow(() -> new NotFoundException("public enterprise", id));
    }

    private MemberProfile scoped(UUID id, ActorScope actor) {
        MemberProfile member = members.findById(id).orElseThrow(() -> new NotFoundException("member", id));
        require(actor != null && actor.subject() != null && !actor.subject().isBlank()
                && member.associationId().equals(actor.associationId()) && MemberAccessPolicy.canUpdate(actor, member));
        if (member.deleted() || "DISABLED".equals(member.status())) throw conflict("企业已停用或删除，不能处理资料");
        return member;
    }

    private MemberProfile lock(UUID id, ActorScope actor) {
        MemberProfile before = scoped(id, actor);
        // Consistent association -> enterprise -> workflow lock order, including publication invalidation triggers.
        List<String> association = jdbc.queryForList("SELECT status FROM association WHERE id=? FOR UPDATE", String.class, before.associationId());
        if (association.size()!=1 || !"ACTIVE".equals(association.getFirst())) throw conflict("协会不可用");
        jdbc.queryForList("SELECT id FROM enterprise WHERE id=? FOR UPDATE", UUID.class, id);
        return scoped(id, actor);
    }

    private Row read(UUID id) {
        return jdbc.query("SELECT version,publication_epoch,published,state_json FROM enterprise_profile_workflow WHERE enterprise_id=?",
                (rs,n) -> new Row(rs.getLong(1),rs.getLong(2),rs.getBoolean(3),decode(rs.getString(4))), id)
                .stream().findFirst().orElse(new Row(0,0,false,State.empty()));
    }
    private Row expected(UUID id, long version) { Row row=read(id); if(row.version()!=version) throw stale(); return row; }
    private View store(MemberProfile member, Row before, State state, boolean published, ActorScope actor, String action) {
        long next = Math.addExact(before.version(), 1);
        String publicName=state.publication()==null?"":state.publication().name();
        String publicCategory=state.publication()==null?"":state.publication().category();
        String draftStatus=state.draft()==null?null:state.draft().status();
        int count = jdbc.update("UPDATE enterprise_profile_workflow SET version=?,state_json=?,published=?,public_name=?,public_category=?,draft_status=? WHERE enterprise_id=? AND version=?",
                next, encode(state), published,publicName,publicCategory,draftStatus, member.id(), before.version());
        if (count==0) {
            if (before.version()!=0) throw stale();
            jdbc.update("INSERT INTO enterprise_profile_workflow(enterprise_id,version,state_json,published,public_name,public_category,draft_status) VALUES(?,?,?,?,?,?,?)",
                    member.id(),next,encode(state),published,publicName,publicCategory,draftStatus);
        }
        // The frozen submission/review snapshot and actor are persisted atomically with the transition.
        audit.record(actor, action, "ENTERPRISE_PROFILE", member.id().toString(), member.associationId(), member.id(),
                Map.of("workflowVersion", next, "state", state));
        return view(member, new Row(next,before.epoch(),published,state),actor);
    }
    private View view(MemberProfile member, Row row, ActorScope actor) {
        Draft draft=row.state().draft(); Approved approved=row.state().approved();
        boolean reviewer=MemberAccessPolicy.canReview(actor,member);
        boolean current=approved!=null && approved.profile().version()==member.version() && "ACTIVE".equals(member.status());
        return new View(member,draft,approved,row.state().publication(),row.version(),row.published(),
                draft==null || !"SUBMITTED".equals(draft.status()),
                reviewer && draft!=null && "SUBMITTED".equals(draft.status()) && independent(draft.editors(),draft.submittedBy(),actor),
                owner(actor,member) && current,
                reviewer && current && independent(approved.editors(),approved.submittedBy(),actor)
                        && approved.consentedAt()!=null && approved.consentEpoch()==row.epoch(),
                owner(actor,member) || reviewer);
    }
    private static boolean owner(ActorScope actor, MemberProfile member) {
        return actor.isEnterpriseAdmin() && !actor.isAssociationStaff() && !actor.isSystemAdmin()
                && member.id().equals(actor.enterpriseId()) && member.associationId().equals(actor.associationId());
    }
    private static boolean independent(Set<String> editors, String submitter, ActorScope actor) {
        return !editors.contains(actor.subject()) && !Objects.equals(submitter,actor.subject());
    }
    private static Draft requiredDraft(Row row) { if(row.state().draft()==null) throw conflict("请先保存草稿"); return row.state().draft(); }
    private static Approved requiredApproved(Row row, MemberProfile member) {
        Approved approved=row.state().approved();
        if(approved==null || !"ACTIVE".equals(member.status())) throw conflict("须先审核资料且企业处于可用状态");
        currentBase(member,approved.profile().version()); return approved;
    }
    private static String reason(String note) { if(note==null || note.isBlank() || note.length()>1000) throw conflict("请填写 1–1000 字的处理原因"); return note.trim(); }
    private static void currentBase(MemberProfile member,long version) { if(member.version()!=version) throw new PreconditionFailedException("正式资料已有新版本，请重新核对草稿，不能覆盖旧版本"); }
    private static void require(boolean allowed) { if(!allowed) throw new ForbiddenException("PROFILE_SCOPE_DENIED","无权处理该资料，或不能审核自己编辑、提交的版本"); }
    private static String normalize(String value) { return value==null || value.isBlank()?null:value.trim().toUpperCase(Locale.ROOT); }
    private static ConflictException conflict(String message) { return new ConflictException(message); }
    private static PreconditionFailedException stale() { return new PreconditionFailedException("资料流程已变化，请重新加载核对后操作"); }
    private State decode(String value) { try {return json.readValue(value,State.class);} catch(JsonProcessingException e){throw new IllegalStateException("Invalid stored profile workflow",e);} }
    private String encode(Object value) { try {return json.writeValueAsString(value);} catch(JsonProcessingException e){throw new IllegalStateException("Cannot encode profile workflow",e);} }
}
