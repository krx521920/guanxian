package com.guanxian.platform.ai.assistant;

import com.guanxian.platform.ai.rag.AiProviderProperties;
import com.guanxian.platform.ai.rag.RagProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.LinkedHashMap;

@Service
public class PersonalModelSettingsService {
    private final PersonalModelRepository repository;
    private final PersonalModelKeyCipher cipher;
    private final RagProperties rag;
    private final AiProviderProperties defaults;
    private final LinkedHashMap<UUID, Long> lastTests = new LinkedHashMap<>();

    public PersonalModelSettingsService(PersonalModelRepository repository, PersonalModelKeyCipher cipher,
                                        RagProperties rag, AiProviderProperties defaults) {
        this.repository = repository;
        this.cipher = cipher;
        this.rag = rag;
        this.defaults = defaults;
    }

    public SettingsView settings(UUID userId) {
        requireUser(userId);
        var saved = repository.find(userId).orElse(null);
        return new SettingsView(PersonalModelProvider.options(), cipher.available(),
                rag.isExternalModelDataEgressEnabled(), saved == null ? null : new SavedView(
                saved.provider().name(), saved.model(), true, saved.enabled(), saved.revision()));
    }

    public SettingsView save(UUID userId, PersonalModelProvider provider, String model, String apiKey,
                             boolean enabled, boolean consent) {
        requireUser(userId);
        if (!cipher.available()) throw new IllegalStateException("管理员尚未配置个人密钥加密服务");
        if (provider == null) throw new IllegalArgumentException("请选择模型厂商");
        if (model == null || !model.strip().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")) {
            throw new IllegalArgumentException("模型 ID 格式无效，请从厂商控制台复制");
        }
        if (enabled && !consent) throw new IllegalArgumentException("启用前请确认数据外发与 API 计费说明");
        var existing = repository.find(userId).orElse(null);
        String encrypted;
        if (apiKey == null || apiKey.isBlank()) {
            if (existing == null || existing.provider() != provider) {
                throw new IllegalArgumentException("首次配置或切换厂商时必须填写新的 API Key");
            }
            // Verify retained ciphertext is decryptable; never accept a corrupted setting silently.
            cipher.decrypt(userId, provider, existing.encryptedKey());
            encrypted = existing.encryptedKey();
        } else {
            String normalized = apiKey.strip();
            if (!normalized.matches("[\\x21-\\x7E]{8,4096}")) {
                throw new IllegalArgumentException("API Key 格式无效，请勿包含空格或换行");
            }
            encrypted = cipher.encrypt(userId, provider, normalized);
        }
        repository.save(new PersonalModelRepository.Stored(userId, provider, model.strip(), encrypted,
                enabled, UUID.randomUUID()));
        return settings(userId);
    }

    public void delete(UUID userId) {
        requireUser(userId);
        repository.delete(userId);
    }

    public java.util.Optional<PersonalModelRepository.Stored> active(UUID userId) {
        requireUser(userId);
        return repository.find(userId).filter(PersonalModelRepository.Stored::enabled);
    }

    public AiProviderProperties properties(PersonalModelRepository.Stored saved) {
        if (!rag.isExternalModelDataEgressEnabled()) {
            throw new IllegalStateException("平台尚未允许模型数据外发，当前继续使用本地模式");
        }
        AiProviderProperties result = new AiProviderProperties();
        result.setEnabled(true);
        result.setEndpoint(saved.provider().endpoint());
        result.setApiKey(cipher.decrypt(saved.userId(), saved.provider(), saved.encryptedKey()));
        result.setModel(saved.model());
        result.setMaxOutputTokens(Math.min(defaults.getMaxOutputTokens(), rag.getMaxOutputTokens()));
        result.setRequestTimeout(Duration.ofSeconds(30));
        // These administrator-configured prices are estimates, never a provider billing quote.
        result.setInputCostPerMillion(defaults.getInputCostPerMillion());
        result.setOutputCostPerMillion(defaults.getOutputCostPerMillion());
        return result;
    }

    public TestResult test(UUID userId, boolean consent) {
        requireUser(userId);
        if (!consent) throw new IllegalArgumentException("请先确认连接测试会向所选厂商发送测试文本并可能计费");
        var saved = repository.find(userId).orElseThrow(() -> new IllegalArgumentException("请先保存模型配置"));
        AiProviderProperties properties = properties(saved);
        reserveTest(userId);
        properties.setMaxOutputTokens(Math.min(256, rag.getMaxOutputTokens()));
        long started = System.nanoTime();
        try {
            ChatMemory memory = org.springframework.ai.chat.memory.MessageWindowChatMemory.builder()
                    .chatMemoryRepository(new BoundedChatMemoryRepository(1)).maxMessages(4).build();
            ChatClient client = SpringAiAssistantConfiguration.createClient(properties, memory);
            // No retrieval, conversation history, business tools, or organization metadata in this probe.
            String content = client.prompt()
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, UUID.randomUUID().toString()))
                    .user("连接测试，请仅回复 OK。")
                    .stream().content().filter(text -> !text.isBlank()).next().block(Duration.ofSeconds(30));
            if (content == null) throw new IllegalStateException();
            return new TestResult(true, "连接成功；仅验证流式文本响应，工具调用能力需在实际聊天中验证。",
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        } catch (RuntimeException exception) {
            // Provider errors can contain echoed credentials/payloads: neither log nor forward them.
            return new TestResult(false, "连接失败，请检查 API Key、模型 ID、地域、余额和网络，或稍后重试。", 0);
        }
    }

    private synchronized void reserveTest(UUID userId) {
        long now = System.nanoTime();
        Long previous = lastTests.get(userId);
        if (previous != null && now - previous < Duration.ofSeconds(30).toNanos()) {
            throw new IllegalArgumentException("连接测试较频繁，请等待 30 秒再试");
        }
        lastTests.remove(userId);
        lastTests.put(userId, now);
        while (lastTests.size() > 500) lastTests.remove(lastTests.keySet().iterator().next());
    }

    private static void requireUser(UUID userId) {
        if (userId == null) throw new IllegalArgumentException("需要有效的登录账号");
    }

    public record SettingsView(List<PersonalModelProvider.Option> providers, boolean storageAvailable,
                               boolean egressAllowed, SavedView saved) { }
    public record SavedView(String provider, String model, boolean hasKey, boolean enabled, UUID revision) { }
    public record TestResult(boolean success, String message, long latencyMs) { }
}
