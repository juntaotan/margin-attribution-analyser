package dev.margintrace.margin_attribution_backend.settings;

import dev.margintrace.margin_attribution_backend.analysis.ai.AiConnectionSettings;
import dev.margintrace.margin_attribution_backend.analysis.ai.AiRuntimeManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiSettingsControllerTests {
    @Mock private AiSettingsService settingsService;

    @Test
    void readsSettingsAndTestsCandidateWithoutSaving() throws Exception {
        when(settingsService.current()).thenReturn(new AiConnectionSettings(
                "host.docker.internal", 8081, "local", Instant.parse("2026-09-27T00:00:00Z")));
        when(settingsService.test("10.0.0.20", 9000, "model-a"))
                .thenReturn(new AiRuntimeManager.ConnectionTest(
                        true, true, 12, "Connected to llama.cpp; the model is ready"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AiSettingsController(settingsService)).build();

        mvc.perform(get("/api/v1/settings/ai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value("http://host.docker.internal:8081"))
                .andExpect(jsonPath("$.persisted").value(true));

        mvc.perform(post("/api/v1/settings/ai/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"host":"10.0.0.20","port":9000,"model":"model-a"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(true))
                .andExpect(jsonPath("$.modelReady").value(true))
                .andExpect(jsonPath("$.latencyMs").value(12));
    }

    @Test
    void returnsBadRequestForInvalidCandidate() throws Exception {
        when(settingsService.test("", 0, "local"))
                .thenThrow(new IllegalArgumentException("Enter a valid llama.cpp address"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AiSettingsController(settingsService)).build();

        mvc.perform(post("/api/v1/settings/ai/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"host":"","port":0,"model":"local"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter a valid llama.cpp address"));
    }
}
