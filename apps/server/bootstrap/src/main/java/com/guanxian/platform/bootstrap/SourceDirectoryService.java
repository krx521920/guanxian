package com.guanxian.platform.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;


import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@org.springframework.stereotype.Service
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "postgres", matchIfMissing = true)
public class SourceDirectoryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public SourceDirectoryService(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
    }

    public enum Kind { ENTERPRISE, POLICY, ASSOCIATION, TENDER, ACTIVITY }
    private static final List<String> EVIDENCE_FIELDS = List.of("记录状态", "记录状态代码", "证据类型", "证据摘要",
            "关联企业及角色", "金额及口径", "分标段信息", "披露份额", "后续结果", "活动时间");
    private static final Map<Kind, List<String>> FIELDS = Map.of(
            Kind.ENTERPRISE, List.of("主体类型","业务领域","主要产品与服务","能力与资质","已知协会关系","网址"),
            Kind.POLICY, List.of("文件类型","发布机构","文号或标准号","发布日期","实施日期","版本说明","日期说明","核心要求","全文状态","官方原文链接"),
            Kind.ASSOCIATION, List.of("领域","协会简介","联系电话","邮箱","地址","官网"),
            Kind.TENDER, List.of("采购人或招标人","项目编号","项目地区","采购类型","发布日期","截止或开标时间",
                    "预算或最高限价","采购内容","关键要求","需求标签","核验日期","原文链接","更正公告",
                    "来源平台","公告类型","预计公告日期","文件获取开始时间","文件获取截止时间","提交截止类型","业务关联说明","核验边界"),
            Kind.ACTIVITY, List.of("发布日期","核验日期","原文链接","核验边界"));
    public record Evidence(String recordId, String title, String checkedOn, String sourceUrl, List<String> supportingUrls) { }
    public record Entry(UUID id, String sourceId, String title, UUID enterpriseId, JsonNode fields, String importedAt,
                        JsonNode originalFields, JsonNode correction, Evidence evidence) { }
    public record Page(List<Entry> items, long total, int page, int size) { }

    @Transactional(readOnly = true, timeout = 10)
    public Page search(Kind kind, String q, int page, int size, ActorScope actor,
                       java.time.LocalDate fromDate, java.time.LocalDate toDate, UUID recordId) {
        validateScope(actor);
        if (kind == null || q == null || (fromDate != null && toDate != null && fromDate.isAfter(toDate))) {
            throw new ApiException("INVALID_DIRECTORY_QUERY", "Invalid source query", HttpStatus.BAD_REQUEST);
        }
        if (page < 0 || page > 10000 || size < 1 || size > 100 || q.length() > 200) {
            throw new ApiException("INVALID_DIRECTORY_QUERY", "Invalid directory query", HttpStatus.BAD_REQUEST);
        }
        var args = new MapSqlParameterSource("kind", kind.name()).addValue("q", q.strip())
                .addValue("size", size).addValue("offset", (long) page * size);
        String where = " WHERE r.kind=:kind AND a.status='ACTIVE'";
        if (actor.associationId() != null) {
            where += " AND r.association_id=:association";
            args.addValue("association", actor.associationId());
        }
        if (recordId != null) {
            where += " AND r.id=:recordId"; args.addValue("recordId", recordId);
        }
        String dateValue = "COALESCE(CASE WHEN jsonb_typeof(r.payload->'publicEvidence'->'record'->'id')='string'"
                + " THEN r.payload->'publicEvidence'->'fields'->>'发布日期' END,"
                + "CASE WHEN jsonb_typeof(r.payload->'correction'->'id')='string'"
                + " THEN r.payload->'correction'->'fields'->>'发布日期' END,r.payload->'source'->>'发布日期')";
        // CASE protects casts and make_date; incomplete and invalid calendar dates cannot pass a date filter.
        if (fromDate != null || toDate != null) {
            where += " AND CASE WHEN " + dateValue + " ~ '^[1-9][0-9]{3}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$'"
                    + " THEN substring(" + dateValue + ",9,2)::int <= extract(day FROM (make_date("
                    + "substring(" + dateValue + ",1,4)::int,substring(" + dateValue + ",6,2)::int,1)"
                    + " + interval '1 month - 1 day')) ELSE FALSE END";
            if (fromDate != null) { where += " AND " + dateValue + ">=:fromDate"; args.addValue("fromDate", fromDate.toString()); }
            if (toDate != null) { where += " AND " + dateValue + "<=:toDate"; args.addValue("toDate", toDate.toString()); }
        }
        // Source snapshots must disappear when their corresponding live record is removed.
        where += " AND (r.enterprise_id IS NULL OR (e.deleted_at IS NULL AND e.status NOT IN ('DISABLED','DELETED')))"
                + " AND (r.policy_id IS NULL OR (p.deleted_at IS NULL AND p.disabled_at IS NULL))";
        if (!actor.isSystemAdmin() && !actor.isAssociationStaff()) {
            where += " AND (r.enterprise_id IS NULL OR (e.status='ACTIVE' AND e.visibility IN ('ASSOCIATION','MEMBERS','PUBLIC')))";
            where += " AND (r.policy_id IS NULL OR (p.status='PUBLISHED' AND p.visibility IN ('ASSOCIATION','MEMBERS','PUBLIC')))";
        }
        where += " AND (:q='' OR position(lower(:q) in lower(r.title || ' ' || concat_ws(' ',"
                + "r.payload->'source'->>'业务领域',r.payload->'source'->>'主要产品与服务',"
                + "r.payload->'source'->>'采购内容',r.payload->'source'->>'协会简介',r.payload->'source'->>'核心要求',"
                + "r.payload->'source'->>'需求标签',r.payload->'source'->>'项目地区',r.payload->'source'->>'业务关联说明',"
                + "r.payload->'publicEvidence'->'record'->>'title',r.payload->'publicEvidence'->'fields'->>'证据摘要',r.payload->'publicEvidence'->'fields'->>'关联企业及角色',"
                + "r.payload->'publicEvidence'->'fields'->>'项目编号',r.payload->'publicEvidence'->'fields'->>'项目地区')))>0)";
        String from = " FROM platform_source_record r JOIN association a ON a.id=r.association_id"
                + " LEFT JOIN enterprise e ON e.id=r.enterprise_id LEFT JOIN policy_document p ON p.id=r.policy_id";
        long total = jdbc.queryForObject("SELECT count(*)" + from + where, args, Long.class);
        var items = jdbc.query("SELECT r.id,r.source_id,r.title,r.enterprise_id,r.payload::text AS payload,r.created_at"
                        + from + where + " ORDER BY CASE WHEN r.kind IN ('TENDER','ACTIVITY') THEN " + dateValue + " END DESC NULLS LAST,r.title,r.id LIMIT :size OFFSET :offset", args,
                (rs, row) -> {
                    JsonNode payload = readPayload(rs.getString("payload"));
                    var original = projectFields(kind, payload.path("source"));
                    var fields = original.deepCopy();
                    JsonNode correction = payload.path("correction");
                    var projectedCorrection = json.createObjectNode();
                    if (correction.isObject() && correction.path("id").isTextual()) {
                        // Apply only the same display allowlist; corrections cannot expose private raw fields.
                        fields.setAll(projectFields(kind, correction.path("fields")));
                        for (String key : List.of("id", "checkedOn", "reason")) {
                            if (correction.path(key).isTextual()) projectedCorrection.put(key, correction.path(key).asText());
                        }
                        var links = projectedCorrection.putArray("evidenceUrls");
                        if (correction.path("evidenceUrls").isArray()) {
                            correction.path("evidenceUrls").forEach(link -> { if (link.isTextual()) links.add(link.asText()); });
                        }
                    }
                    Evidence evidence = null;
                    JsonNode publicEvidence = payload.path("publicEvidence");
                    if ((kind == Kind.TENDER || kind == Kind.ACTIVITY) && publicEvidence.path("record").path("id").isTextual()) {
                        // Evidence augments display only; never exposes raw relations, private profiles or consent fields.
                        fields.setAll(projectFields(kind, publicEvidence.path("fields")));
                        var record = publicEvidence.path("record");
                        var links = new java.util.ArrayList<String>();
                        if (publicEvidence.path("supportingUrls").isArray()) publicEvidence.path("supportingUrls")
                                .forEach(link -> { if (link.isTextual()) links.add(link.asText()); });
                        evidence = new Evidence(record.path("id").asText(), record.path("title").asText(),
                                record.path("checkedOn").asText(), record.path("sourceUrl").asText(), List.copyOf(links));
                    }
                    return new Entry(rs.getObject("id", UUID.class), rs.getString("source_id"), rs.getString("title"),
                            rs.getObject("enterprise_id", UUID.class), fields,
                            rs.getTimestamp("created_at").toInstant().toString(),
                            projectedCorrection.isEmpty() && evidence == null ? null : original,
                            projectedCorrection.isEmpty() ? null : projectedCorrection, evidence);
                });
        return new Page(items, total, page, size);
    }

    static void validateScope(ActorScope actor) {
        if (actor == null || (!actor.isSystemAdmin() && !actor.isAssociationStaff()
                && !actor.hasRole("ENTERPRISE_ADMIN") && !actor.hasRole("ENTERPRISE_MEMBER"))
                || (!actor.isSystemAdmin() && actor.associationId() == null)) {
            throw new ForbiddenException("SOURCE_DIRECTORY_SCOPE_REQUIRED", "Verified association scope is required");
        }
    }

    private JsonNode readPayload(String value) {
        try {
            return json.readTree(value);
        }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored source record", e); }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode projectFields(Kind kind, JsonNode source) {
        var result = json.createObjectNode();
        for (String field : FIELDS.get(kind)) {
            if (source.path(field).isTextual()) result.put(field, source.path(field).asText());
        }
        if (kind == Kind.TENDER || kind == Kind.ACTIVITY) for (String field : EVIDENCE_FIELDS) {
            if (source.path(field).isTextual()) result.put(field, source.path(field).asText());
        }
        return result;
    }
}
