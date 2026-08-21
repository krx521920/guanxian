package com.guanxian.platform.member.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.shared.error.ConflictException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "guanxian.member.repository", havingValue = "postgres", matchIfMissing = true)
class PostgresMemberRepository implements MemberRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final String SELECT_FIELDS = """
            SELECT id, association_id, name, unified_social_credit_code, category, address,
                   contact_name, contact_phone, description, capabilities, products,
                   cooperation_needs, visibility, status, version, created_at, updated_at
            FROM enterprise
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String associationName;

    PostgresMemberRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${guanxian.member.association-name:北京地下管线协会}") String associationName) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.associationName = associationName;
    }

    @Override
    public List<MemberProfile> findAll() {
        return jdbc.query(SELECT_FIELDS, this::mapMember);
    }

    @Override
    public Optional<MemberProfile> findById(UUID id) {
        return jdbc.query(SELECT_FIELDS + " WHERE id = :id", new MapSqlParameterSource("id", id), this::mapMember)
                .stream().findFirst();
    }

    @Override
    public UUID defaultAssociationId() {
        List<UUID> ids = jdbc.queryForList(
                "SELECT id FROM association WHERE name = :name AND status = 'ACTIVE'",
                new MapSqlParameterSource("name", associationName), UUID.class);
        if (ids.size() != 1) {
            throw new IllegalStateException("configured association does not exist or is inactive: " + associationName);
        }
        return ids.getFirst();
    }

    @Override
    public void insert(MemberProfile member) {
        String sql = """
                INSERT INTO enterprise (
                    id, association_id, unified_social_credit_code, name, short_name,
                    description, enterprise_roles, service_scenarios, visibility, status,
                    version, created_at, updated_at, category, address, contact_name,
                    contact_phone, capabilities, products, cooperation_needs)
                VALUES (:id, :associationId, :creditCode, :name, NULL,
                    :introduction, '[]'::jsonb, '[]'::jsonb, :visibility, :status,
                    :version, :createdAt, :updatedAt, :category, :address, :contactName,
                    :contactPhone, CAST(:capabilities AS jsonb), CAST(:products AS jsonb),
                    CAST(:cooperationNeeds AS jsonb))
                """;
        try {
            jdbc.update(sql, parameters(member));
        } catch (DataIntegrityViolationException exception) {
            throw translateIntegrityViolation(exception);
        }
    }

    @Override
    public boolean update(MemberProfile member, long expectedVersion) {
        String sql = """
                UPDATE enterprise
                SET unified_social_credit_code = :creditCode,
                    name = :name,
                    description = :introduction,
                    visibility = :visibility,
                    status = :status,
                    version = :version,
                    updated_at = :updatedAt,
                    category = :category,
                    address = :address,
                    contact_name = :contactName,
                    contact_phone = :contactPhone,
                    capabilities = CAST(:capabilities AS jsonb),
                    products = CAST(:products AS jsonb),
                    cooperation_needs = CAST(:cooperationNeeds AS jsonb)
                WHERE id = :id AND association_id = :associationId AND version = :expectedVersion
                """;
        try {
            return jdbc.update(sql, parameters(member).addValue("expectedVersion", expectedVersion)) == 1;
        } catch (DataIntegrityViolationException exception) {
            throw translateIntegrityViolation(exception);
        }
    }

    @Override
    public boolean deleteById(UUID id, long expectedVersion) {
        return jdbc.update(
                "DELETE FROM enterprise WHERE id = :id AND version = :expectedVersion",
                new MapSqlParameterSource().addValue("id", id).addValue("expectedVersion", expectedVersion)) == 1;
    }

    private MapSqlParameterSource parameters(MemberProfile member) {
        return new MapSqlParameterSource()
                .addValue("id", member.id())
                .addValue("associationId", member.associationId())
                .addValue("creditCode", member.unifiedSocialCreditCode())
                .addValue("name", member.name())
                .addValue("introduction", member.introduction())
                .addValue("visibility", member.visibility())
                .addValue("status", member.status())
                .addValue("version", member.version())
                .addValue("createdAt", Timestamp.from(member.createdAt()))
                .addValue("updatedAt", Timestamp.from(member.updatedAt()))
                .addValue("category", member.category())
                .addValue("address", member.address())
                .addValue("contactName", member.contactName())
                .addValue("contactPhone", member.contactPhone())
                .addValue("capabilities", writeList(member.capabilities()))
                .addValue("products", writeList(member.products()))
                .addValue("cooperationNeeds", writeList(member.cooperationNeeds()));
    }

    private MemberProfile mapMember(ResultSet resultSet, int rowNumber) throws SQLException {
        return new MemberProfile(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("association_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("unified_social_credit_code"),
                resultSet.getString("category"),
                resultSet.getString("address"),
                resultSet.getString("contact_name"),
                resultSet.getString("contact_phone"),
                resultSet.getString("description"),
                readList(resultSet.getString("capabilities")),
                readList(resultSet.getString("products")),
                readList(resultSet.getString("cooperation_needs")),
                resultSet.getString("visibility"),
                resultSet.getString("status"),
                resultSet.getLong("version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private String writeList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("member list could not be serialized", exception);
        }
    }

    private List<String> readList(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(json, STRING_LIST));
        } catch (JsonProcessingException exception) {
            throw new SQLException("stored member list is invalid JSON", exception);
        }
    }

    private static ConflictException duplicateMember() {
        return new ConflictException("member name or unified social credit code already exists");
    }

    private static RuntimeException translateIntegrityViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                return duplicateMember();
            }
            cause = cause.getCause();
        }
        return exception;
    }
}
