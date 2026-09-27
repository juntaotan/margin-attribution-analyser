package dev.margintrace.margin_attribution_backend.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReportTemplateControllerTests {

    @Mock
    private ReportTemplateStore templateStore;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ReportTemplateController controller = new ReportTemplateController(templateStore);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getMetadataReturnsTemplateInfo() throws Exception {
        ReportTemplateStore.TemplateMetadata meta = new ReportTemplateStore.TemplateMetadata(
                "master-template.docx",
                1024L,
                "2026-09-28T00:00:00Z",
                false,
                List.of(new ReportTemplateStore.PlaceholderItem("Revenue", "Sales Revenue", true, "12,000")),
                6,
                6,
                1L
        );

        when(templateStore.getMetadata()).thenReturn(meta);

        mvc.perform(get("/api/v1/settings/template"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("master-template.docx"))
                .andExpect(jsonPath("$.matchedCount").value(6))
                .andExpect(jsonPath("$.isCustom").value(false));
    }

    @Test
    void uploadTemplateStoresFileAndReturnsMetadata() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "custom-template.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{1, 2, 3}
        );

        ReportTemplateStore.TemplateMetadata meta = new ReportTemplateStore.TemplateMetadata(
                "custom-template.docx",
                3L,
                "2026-09-28T01:00:00Z",
                true,
                List.of(),
                6,
                6,
                2L
        );

        when(templateStore.uploadMasterTemplate(eq("custom-template.docx"), any()))
                .thenReturn(meta);

        mvc.perform(multipart("/api/v1/settings/template/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("custom-template.docx"))
                .andExpect(jsonPath("$.isCustom").value(true));

        verify(templateStore).uploadMasterTemplate(eq("custom-template.docx"), any());
    }

    @Test
    void downloadTemplateReturnsFile() throws Exception {
        byte[] dummyBytes = new byte[]{10, 20, 30};
        ReportTemplateStore.TemplateMetadata meta = new ReportTemplateStore.TemplateMetadata(
                "master-template.docx",
                3L,
                "2026-09-28T00:00:00Z",
                false,
                List.of(),
                6,
                6,
                1L
        );

        when(templateStore.getMetadata()).thenReturn(meta);
        when(templateStore.loadOrCreateMasterTemplate()).thenReturn(dummyBytes);

        mvc.perform(get("/api/v1/settings/template/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"master-template.docx\""))
                .andExpect(content().bytes(dummyBytes));
    }

    @Test
    void resetTemplateCallsStoreReset() throws Exception {
        ReportTemplateStore.TemplateMetadata meta = new ReportTemplateStore.TemplateMetadata(
                "master-management-commentary-template.docx",
                1000L,
                "2026-09-28T00:00:00Z",
                false,
                List.of(),
                6,
                6,
                3L
        );

        when(templateStore.resetToDefault()).thenReturn(meta);

        mvc.perform(post("/api/v1/settings/template/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCustom").value(false));

        verify(templateStore).resetToDefault();
    }
}
