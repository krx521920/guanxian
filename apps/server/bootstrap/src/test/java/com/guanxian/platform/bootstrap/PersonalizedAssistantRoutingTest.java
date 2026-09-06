package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.assistant.*;
import com.guanxian.platform.ai.rag.AiProviderProperties;
import com.guanxian.platform.ai.rag.RagProperties;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class PersonalizedAssistantRoutingTest {
    @Test void enabledPersonalModelWorksWhenPlatformModelIsDisabledWithoutGlobalMutation() {
        var platform = mock(SpringAiAssistantChatClient.class);
        when(platform.enabled()).thenReturn(false);
        var settings = mock(PersonalModelSettingsService.class);
        var rag = new RagProperties();
        rag.setExternalModelDataEgressEnabled(true);
        var memory = MessageWindowChatMemory.builder().chatMemoryRepository(new BoundedChatMemoryRepository(4)).build();
        var router = new PersonalizedAssistantChatClient(platform, settings, memory, List.of(), rag);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        var saved = new PersonalModelRepository.Stored(alice, PersonalModelProvider.KIMI, "test-model", "ciphertext", true, UUID.randomUUID());
        when(settings.active(alice)).thenReturn(Optional.of(saved));
        when(settings.active(bob)).thenReturn(Optional.empty());
        var props = new AiProviderProperties();
        props.setEnabled(true);
        props.setEndpoint(PersonalModelProvider.KIMI.endpoint());
        props.setApiKey("test-only-key");
        props.setModel("test-model");
        when(settings.properties(saved)).thenReturn(props);
        var personal = router.forAccess(access(alice));
        assertThat(personal.enabled()).isTrue();
        assertThat(personal.providerName()).isEqualTo("personal-kimi");
        assertThat(router.forAccess(access(bob))).isSameAs(platform);
        assertThat(platform.enabled()).isFalse();
        verify(settings).active(alice);
        verify(settings).active(bob);
    }

    @Test void organizationEgressGateWinsBeforeAnyPersonalSecretIsRead() {
        var platform = mock(SpringAiAssistantChatClient.class);
        var settings = mock(PersonalModelSettingsService.class);
        var router = new PersonalizedAssistantChatClient(platform, settings,
                MessageWindowChatMemory.builder().build(), List.of(), new RagProperties());
        assertThat(router.forAccess(access(UUID.randomUUID()))).isSameAs(platform);
        verifyNoInteractions(settings);
    }

    private static AssistantAccessContext access(UUID userId) {
        return new AssistantAccessContext(new ActorScope(userId, userId.toString(), "test-user", UUID.randomUUID(),
                null, Set.of("ASSOCIATION_OPERATOR"), Set.of()), Set.of("POLICY_READ"));
    }
}
