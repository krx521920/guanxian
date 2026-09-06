package com.guanxian.platform.ai.assistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class PersonalModelRepository {
    private final JdbcTemplate jdbc;
    public PersonalModelRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Stored> find(UUID userId) {
        return jdbc.query("SELECT provider, model, encrypted_key, enabled, revision FROM personal_model_setting WHERE user_id = ?",
                (rs, row) -> new Stored(userId, PersonalModelProvider.valueOf(rs.getString("provider")),
                        rs.getString("model"), rs.getString("encrypted_key"), rs.getBoolean("enabled"),
                        rs.getObject("revision", UUID.class)), userId).stream().findFirst();
    }

    public void save(Stored setting) {
        jdbc.update("""
                INSERT INTO personal_model_setting (user_id, provider, model, encrypted_key, enabled, revision, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (user_id) DO UPDATE SET provider = EXCLUDED.provider, model = EXCLUDED.model,
                  encrypted_key = EXCLUDED.encrypted_key, enabled = EXCLUDED.enabled,
                  revision = EXCLUDED.revision, updated_at = CURRENT_TIMESTAMP
                """, setting.userId(), setting.provider().name(), setting.model(), setting.encryptedKey(),
                setting.enabled(), setting.revision());
    }

    public void delete(UUID userId) { jdbc.update("DELETE FROM personal_model_setting WHERE user_id = ?", userId); }

    public record Stored(UUID userId, PersonalModelProvider provider, String model, String encryptedKey,
                         boolean enabled, UUID revision) {
        @Override public String toString() { return "PersonalModelSetting[redacted]"; }
    }
}
