package com.guanxian.platform.tender;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "postgres", matchIfMissing = true)
class PostgresTenderStore implements TenderStore {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final String SELECT = """
            SELECT t.id, t.association_id, t.title, t.purchaser, t.agency, t.region, t.category,
                   t.keywords::text AS keywords, t.budget, t.publish_date, t.deadline,
                   t.source, t.source_url, t.status, t.version, t.created_at, t.updated_at
              FROM tender t
            """;
    private static final String PUSH_SELECT = """
            SELECT p.id, p.tender_id, p.tender_title, p.enterprise_id, p.enterprise_name,
                   p.pushed_by_subject, p.pushed_at, p.status, p.version
              FROM tender_push p
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<TenderView> mapper = this::mapTender;
    private final RowMapper<TenderPushView> pushMapper = this::mapPush;

    PostgresTenderStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<TenderView> findAll(
            String query, String category, String region, String status, int offset, int limit) {
        MapSqlParameterSource params = filterParams(query, category, region, status)
                .addValue("offset", offset).addValue("limit", limit);
        return jdbc.query(SELECT + whereClause(query, category, region, status)
                        + " ORDER BY t.publish_date DESC, t.id LIMIT :limit OFFSET :offset",
                params, mapper);
    }

    @Override
    public long countAll(String query, String category, String region, String status) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM tender t"
                        + whereClause(query, category, region, status),
                filterParams(query, category, region, status), Long.class);
        return total == null ? 0 : total;
    }

    @Override
    public Optional<TenderView> findById(UUID id) {
        return jdbc.query(SELECT + " WHERE t.id = :id", new MapSqlParameterSource("id", id), mapper)
                .stream().findFirst();
    }

    @Override
    public TenderView insert(UUID associationId, TenderUpsertRequest request, ActorScope actor) {
        return jdbc.queryForObject("""
                INSERT INTO tender (
                    association_id, title, purchaser, agency, region, category, keywords, budget,
                    publish_date, deadline, source, source_url, status,
                    created_by_subject, updated_by_subject)
                VALUES (
                    :associationId, :title, :purchaser, :agency, :region, :category,
                    CAST(:keywords AS jsonb), :budget, :publishDate, :deadline,
                    :source, :sourceUrl, :status, :subject, :subject)
                RETURNING id, association_id, title, purchaser, agency, region, category,
                          keywords::text AS keywords, budget, publish_date, deadline,
                          source, source_url, status, version, created_at, updated_at
                """, requestParams(request, actor).addValue("associationId", associationId), mapper);
    }

    @Override
    public Optional<TenderView> update(
            UUID id, long expectedVersion, TenderUpsertRequest request, ActorScope actor) {
        return jdbc.query("""
                UPDATE tender
                   SET title = :title, purchaser = :purchaser, agency = :agency, region = :region,
                       category = :category, keywords = CAST(:keywords AS jsonb), budget = :budget,
                       publish_date = :publishDate, deadline = :deadline, source = :source,
                       source_url = :sourceUrl, status = :status,
                       updated_by_subject = :subject, updated_at = now(), version = version + 1
                 WHERE id = :id AND version = :version
                RETURNING id, association_id, title, purchaser, agency, region, category,
                          keywords::text AS keywords, budget, publish_date, deadline,
                          source, source_url, status, version, created_at, updated_at
                """, requestParams(request, actor).addValue("id", id).addValue("version", expectedVersion), mapper)
                .stream().findFirst();
    }

    @Override
    public TenderPushView upsertPush(
            UUID tenderId, String tenderTitle, UUID enterpriseId, String enterpriseName, ActorScope actor) {
        return jdbc.queryForObject("""
                INSERT INTO tender_push (
                    tender_id, tender_title, enterprise_id, enterprise_name,
                    pushed_by_subject, status)
                VALUES (:tenderId, :tenderTitle, :enterpriseId, :enterpriseName, :subject, 'PUSHED')
                ON CONFLICT (tender_id, enterprise_id) DO UPDATE SET tender_id = EXCLUDED.tender_id
                RETURNING id, tender_id, tender_title, enterprise_id, enterprise_name,
                          pushed_by_subject, pushed_at, status, version
                """, new MapSqlParameterSource()
                .addValue("tenderId", tenderId).addValue("tenderTitle", tenderTitle)
                .addValue("enterpriseId", enterpriseId).addValue("enterpriseName", enterpriseName)
                .addValue("subject", actor.subject()), pushMapper);
    }

    @Override
    public List<TenderPushView> findPushes(UUID tenderId) {
        return jdbc.query(PUSH_SELECT + " WHERE p.tender_id = :tenderId"
                        + " ORDER BY p.pushed_at DESC, p.id",
                new MapSqlParameterSource("tenderId", tenderId), pushMapper);
    }

    @Override
    public List<UUID> listPushedEnterpriseIds(UUID tenderId) {
        return jdbc.queryForList("SELECT p.enterprise_id FROM tender_push p WHERE p.tender_id = :tenderId",
                new MapSqlParameterSource("tenderId", tenderId), UUID.class);
    }

    @Override
    public List<TenderView> findRelevant(List<String> enterpriseKeywords, String region, int offset, int limit) {
        List<String> keywords = normalizeKeywords(enterpriseKeywords);
        if (keywords.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("offset", offset).addValue("limit", limit);
        return jdbc.query(SELECT + relevantWhere(keywords, region, params)
                        + " ORDER BY t.publish_date DESC, t.id LIMIT :limit OFFSET :offset",
                params, mapper);
    }

    @Override
    public long countRelevant(List<String> enterpriseKeywords, String region) {
        List<String> keywords = normalizeKeywords(enterpriseKeywords);
        if (keywords.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long total = jdbc.queryForObject("SELECT count(*) FROM tender t"
                + relevantWhere(keywords, region, params), params, Long.class);
        return total == null ? 0 : total;
    }

    private TenderView mapTender(ResultSet rs, int row) throws SQLException {
        return new TenderView(
                rs.getObject("id", UUID.class).toString(),
                rs.getObject("association_id", UUID.class),
                rs.getString("title"),
                rs.getString("purchaser"),
                rs.getString("agency"),
                rs.getString("region"),
                rs.getString("category"),
                readList(rs.getString("keywords")),
                rs.getObject("budget", Long.class),
                date(rs, "publish_date"),
                date(rs, "deadline"),
                rs.getString("source"),
                rs.getString("source_url"),
                rs.getString("status"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private TenderPushView mapPush(ResultSet rs, int row) throws SQLException {
        return new TenderPushView(
                rs.getObject("id", UUID.class).toString(),
                rs.getObject("tender_id", UUID.class),
                rs.getString("tender_title"),
                rs.getObject("enterprise_id", UUID.class),
                rs.getString("enterprise_name"),
                rs.getString("pushed_by_subject"),
                rs.getTimestamp("pushed_at").toInstant(),
                rs.getString("status"),
                rs.getLong("version"));
    }

    private MapSqlParameterSource requestParams(TenderUpsertRequest request, ActorScope actor) {
        try {
            return new MapSqlParameterSource()
                    .addValue("title", request.title().trim())
                    .addValue("purchaser", request.purchaser().trim())
                    .addValue("agency", clean(request.agency()))
                    .addValue("region", clean(request.region()))
                    .addValue("category", request.category().trim())
                    .addValue("keywords", objectMapper.writeValueAsString(list(request.keywords())))
                    .addValue("budget", request.budget())
                    .addValue("publishDate", request.publishDate())
                    .addValue("deadline", request.deadline())
                    .addValue("source", clean(request.source()))
                    .addValue("sourceUrl", clean(request.sourceUrl()))
                    .addValue("status", status(request.status()))
                    .addValue("subject", actor.subject());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("tender keywords could not be serialized", exception);
        }
    }

    private MapSqlParameterSource filterParams(String query, String category, String region, String status) {
        return new MapSqlParameterSource()
                .addValue("query", query == null ? null : "%" + query.trim() + "%")
                .addValue("category", category)
                .addValue("region", region)
                .addValue("status", status);
    }

    private String whereClause(String query, String category, String region, String status) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (query != null && !query.isBlank()) {
            sql.append("""
                     AND (t.title ILIKE :query OR t.purchaser ILIKE :query OR t.agency ILIKE :query
                          OR t.region ILIKE :query OR t.category ILIKE :query OR t.source ILIKE :query
                          OR t.keywords::text ILIKE :query)
                    """);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND t.category = :category");
        }
        if (region != null && !region.isBlank()) {
            sql.append(" AND t.region = :region");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND t.status = :status");
        }
        return sql.toString();
    }

    private String relevantWhere(List<String> keywords, String region, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder(" WHERE t.status = 'ACTIVE' AND (");
        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("t.category ILIKE :kw").append(i)
                    .append(" OR t.keywords::text ILIKE :kw").append(i);
            params.addValue("kw" + i, "%" + keywords.get(i) + "%");
        }
        sql.append(")");
        if (region != null && !region.isBlank()) {
            sql.append(" AND t.region = :region");
            params.addValue("region", region.trim());
        }
        return sql.toString();
    }

    private List<String> readList(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException("stored tender keywords are invalid JSON", exception);
        }
    }

    private static List<String> normalizeKeywords(List<String> keywords) {
        if (keywords == null) {
            return List.of();
        }
        return keywords.stream().map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private static List<String> list(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String status(String value) {
        return value == null || value.isBlank() ? "ACTIVE" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }
}
