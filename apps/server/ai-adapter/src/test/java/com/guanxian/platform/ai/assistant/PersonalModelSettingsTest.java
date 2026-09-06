package com.guanxian.platform.ai.assistant;

import com.guanxian.platform.ai.rag.AiProviderProperties;
import com.guanxian.platform.ai.rag.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PersonalModelSettingsTest {
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final MemoryRepository repository = new MemoryRepository();
    private final PersonalModelKeyCipher cipher = new PersonalModelKeyCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final RagProperties rag = new RagProperties();
    private final PersonalModelSettingsService service = new PersonalModelSettingsService(repository, cipher, rag, new AiProviderProperties());

    @Test void encryptionIsRandomizedAndBoundToOwnerAndProvider() {
        String first = cipher.encrypt(alice, PersonalModelProvider.DEEPSEEK, "test-secret-alice");
        String second = cipher.encrypt(alice, PersonalModelProvider.DEEPSEEK, "test-secret-alice");
        assertNotEquals(first, second);
        assertFalse(first.contains("test-secret-alice"));
        assertEquals("test-secret-alice", cipher.decrypt(alice, PersonalModelProvider.DEEPSEEK, first));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(bob, PersonalModelProvider.DEEPSEEK, first));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(alice, PersonalModelProvider.KIMI, first));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(alice, PersonalModelProvider.DEEPSEEK, first.substring(0, first.length() - 4) + "AAAA"));
    }

    @Test void missingOrInvalidMasterKeyNeverStoresPlaintext() {
        var unavailable = new PersonalModelKeyCipher("");
        assertFalse(unavailable.available());
        assertThrows(IllegalStateException.class, () -> unavailable.encrypt(alice, PersonalModelProvider.KIMI, "test-secret"));
        assertThrows(IllegalStateException.class, () -> new PersonalModelKeyCipher("invalid-key"));
        var noStorage = new PersonalModelSettingsService(repository, unavailable, rag, new AiProviderProperties());
        assertThrows(IllegalStateException.class, () -> noStorage.save(alice, PersonalModelProvider.KIMI, "model", "test-secret", true, true));
        assertTrue(repository.rows.isEmpty());
    }

    @Test void settingsArePersonalEncryptedAndNeverReturnAKey() {
        var view = saveAlice();
        assertTrue(view.saved().hasKey());
        assertNull(service.settings(bob).saved());
        assertFalse(view.toString().contains("test-secret-alice"));
        var stored = repository.find(alice).orElseThrow();
        assertFalse(stored.encryptedKey().contains("test-secret-alice"));
        assertFalse(stored.toString().contains(stored.encryptedKey()));
        assertEquals(alice, stored.userId());
    }

    @Test void retainingKeyIsAllowedOnlyForSameProviderAndCreatesNewMemoryRevision() {
        var before = saveAlice().saved();
        var after = service.save(alice, PersonalModelProvider.DEEPSEEK, "another-model", "", false, false).saved();
        assertNotEquals(before.revision(), after.revision());
        assertEquals("test-secret-alice", cipher.decrypt(alice, PersonalModelProvider.DEEPSEEK, repository.find(alice).orElseThrow().encryptedKey()));
        assertTrue(service.active(alice).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.save(alice, PersonalModelProvider.KIMI, "model", "", true, true));
        assertThrows(IllegalArgumentException.class, () -> service.save(bob, PersonalModelProvider.DEEPSEEK, "model", "", true, true));
    }

    @Test void consentAndHeaderInjectionAreValidatedServerSide() {
        assertThrows(IllegalArgumentException.class, () -> service.save(alice, PersonalModelProvider.KIMI, "model", "test-secret", true, false));
        assertThrows(IllegalArgumentException.class, () -> service.save(alice, PersonalModelProvider.KIMI, "model", "test-secret\r\nX-Injected: value", true, true));
        assertThrows(IllegalArgumentException.class, () -> service.save(alice, PersonalModelProvider.KIMI, "../../bad?token=secret", "test-secret", true, true));
        assertThrows(IllegalArgumentException.class, () -> service.save(null, PersonalModelProvider.KIMI, "model", "test-secret", true, true));
        assertTrue(repository.rows.isEmpty());
    }

    @Test void platformEgressGateStillBlocksPersonalModelsAndConnectionTests() {
        saveAlice();
        assertFalse(service.settings(alice).egressAllowed());
        assertThrows(IllegalStateException.class, () -> service.properties(repository.find(alice).orElseThrow()));
        assertThrows(IllegalStateException.class, () -> service.test(alice, true));
        assertThrows(IllegalArgumentException.class, () -> service.test(alice, false));
    }

    @Test void eachProviderResolvesToItsOfficialDestinationAndOwnKey() {
        rag.setExternalModelDataEgressEnabled(true);
        for (PersonalModelProvider provider : PersonalModelProvider.values()) {
            service.save(alice, provider, "user-selected-model", "test-secret-alice", true, true);
            var config = service.properties(repository.find(alice).orElseThrow());
            assertEquals(provider.endpoint(), config.getEndpoint());
            assertEquals("user-selected-model", config.getModel());
            assertEquals("test-secret-alice", config.getApiKey());
            assertTrue(config.isEnabled());
            assertTrue(config.getEndpoint().startsWith("https://"));
        }
        assertEquals(4, PersonalModelProvider.options().size());
    }

    @Test void deletingDoesNotAffectAnotherUsersConfiguration() {
        saveAlice();
        service.save(bob, PersonalModelProvider.KIMI, "model", "test-secret-bob", true, true);
        service.delete(alice);
        assertNull(service.settings(alice).saved());
        assertNotNull(service.settings(bob).saved());
        service.delete(alice);
    }

    private PersonalModelSettingsService.SettingsView saveAlice() {
        return service.save(alice, PersonalModelProvider.DEEPSEEK, "test-model", "test-secret-alice", true, true);
    }

    static final class MemoryRepository extends PersonalModelRepository {
        final Map<UUID, Stored> rows = new HashMap<>();
        MemoryRepository() { super(null); }
        @Override public Optional<Stored> find(UUID id) { return Optional.ofNullable(rows.get(id)); }
        @Override public void save(Stored value) { rows.put(value.userId(), value); }
        @Override public void delete(UUID id) { rows.remove(id); }
    }
}
