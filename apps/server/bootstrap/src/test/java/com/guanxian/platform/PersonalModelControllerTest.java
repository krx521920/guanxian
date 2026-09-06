package com.guanxian.platform;

import com.guanxian.platform.ai.assistant.PersonalModelProvider;
import com.guanxian.platform.ai.assistant.PersonalModelSettingsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PersonalModelControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PersonalModelSettingsService service;

    @Test void anonymousUsersCannotReadSaveTestOrDeletePersonalCredentials() throws Exception {
        mvc.perform(get("/api/v1/assistant/model-settings")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/assistant/model-settings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/assistant/model-settings/test").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/assistant/model-settings")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void metadataUsesVerifiedIdentityAndIsNotCacheable() throws Exception {
        when(service.settings(any())).thenReturn(new PersonalModelSettingsService.SettingsView(
                PersonalModelProvider.options(), true, false, null));
        mvc.perform(get("/api/v1/assistant/model-settings").with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.providers.length()").value(4))
                .andExpect(jsonPath("$.data.saved").isEmpty());
        ArgumentCaptor<UUID> owner = ArgumentCaptor.forClass(UUID.class);
        verify(service).settings(owner.capture());
        assertThat(owner.getValue()).isNotNull();
    }

    @Test void injectedOwnerDoesNotOverrideCurrentAccountAndKeyIsNotReflected() throws Exception {
        UUID injected = UUID.randomUUID();
        var safe = new PersonalModelSettingsService.SettingsView(PersonalModelProvider.options(), true, true,
                new PersonalModelSettingsService.SavedView("KIMI", "test-model", true, true, UUID.randomUUID()));
        when(service.save(any(), any(), anyString(), anyString(), anyBoolean(), anyBoolean())).thenReturn(safe);
        mvc.perform(put("/api/v1/assistant/model-settings").with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"provider":"KIMI","model":"test-model","apiKey":"test-only-private-key",
                         "enabled":true,"externalDataConsent":true,"userId":"%s"}
                        """.formatted(injected)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-only-private-key"))))
                .andExpect(jsonPath("$.data.saved.hasKey").value(true));
        ArgumentCaptor<UUID> owner = ArgumentCaptor.forClass(UUID.class);
        verify(service).save(owner.capture(), eq(PersonalModelProvider.KIMI), eq("test-model"),
                eq("test-only-private-key"), eq(true), eq(true));
        assertThat(owner.getValue()).isNotEqualTo(injected);
    }

    @Test void arbitraryProviderAndOversizedKeysAreRejectedWithoutEchoingPayload() throws Exception {
        mvc.perform(put("/api/v1/assistant/model-settings").with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"provider":"https://127.0.0.1/steal","model":"x","apiKey":"test-secret"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-secret"))));
        mvc.perform(put("/api/v1/assistant/model-settings").with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"provider":"KIMI","model":"x","apiKey":"%s"}
                        """.formatted("x".repeat(4097))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("x".repeat(100)))));
        verifyNoInteractions(service);
    }
}
