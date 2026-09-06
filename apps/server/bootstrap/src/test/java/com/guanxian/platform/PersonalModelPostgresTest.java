package com.guanxian.platform;

import com.guanxian.platform.ai.assistant.PersonalModelKeyCipher;
import com.guanxian.platform.ai.assistant.PersonalModelProvider;
import com.guanxian.platform.ai.assistant.PersonalModelRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PersonalModelPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test void migratesAndPersistsEncryptedUpsertsWithOwnerIsolationAndDeletion() {
        var datasource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(datasource).load().migrate();
        var jdbc = new JdbcTemplate(datasource);
        var repository = new PersonalModelRepository(jdbc);
        var cipher = new PersonalModelKeyCipher(Base64.getEncoder().encodeToString(new byte[32]));
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        jdbc.update("INSERT INTO user_account(id, username, display_name) VALUES (?, ?, ?)", alice, "personal-model-alice", "Alice fixture");
        jdbc.update("INSERT INTO user_account(id, username, display_name) VALUES (?, ?, ?)", bob, "personal-model-bob", "Bob fixture");
        String encrypted = cipher.encrypt(alice, PersonalModelProvider.KIMI, "test-only-key");
        repository.save(new PersonalModelRepository.Stored(alice, PersonalModelProvider.KIMI, "first-model", encrypted, true, UUID.randomUUID()));
        assertThat(repository.find(bob)).isEmpty();
        var saved = repository.find(alice).orElseThrow();
        assertThat(saved.encryptedKey()).doesNotContain("test-only-key");
        assertThat(cipher.decrypt(alice, saved.provider(), saved.encryptedKey())).isEqualTo("test-only-key");
        UUID revision = UUID.randomUUID();
        repository.save(new PersonalModelRepository.Stored(alice, PersonalModelProvider.KIMI, "second-model", encrypted, false, revision));
        assertThat(repository.find(alice).orElseThrow().revision()).isEqualTo(revision);
        assertThat(repository.find(alice).orElseThrow().model()).isEqualTo("second-model");
        assertThat(repository.find(alice).orElseThrow().enabled()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_setting", Integer.class)).isEqualTo(1);
        repository.delete(alice);
        assertThat(repository.find(alice)).isEmpty();
    }
}
