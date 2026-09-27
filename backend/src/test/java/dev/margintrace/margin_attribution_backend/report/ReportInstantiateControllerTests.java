package dev.margintrace.margin_attribution_backend.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReportInstantiateControllerTests {

    @Mock
    private ReportTemplateStore templateStore;
    @Mock
    private ReportPlaceholderResolutionService resolutionService;
    @Mock
    private ReportContentControlUpdater contentControlUpdater;
    @Mock
    private ReportDocumentStore documentStore;
    @Mock
    private OnlyOfficeController onlyOfficeController;
    @Mock
    private ReportPlaceholderService placeholderService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ReportInstantiateController controller = new ReportInstantiateController(
                templateStore, resolutionService, contentControlUpdater, documentStore, onlyOfficeController, placeholderService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void previewValuesReturnsResolvedMetrics() throws Exception {
        ReportPlaceholderResolutionService.PlaceholderValues values =
                new ReportPlaceholderResolutionService.PlaceholderValues(
                        "2026-08-01 to 2026-08-31",
                        "2026-07-01 to 2026-07-31",
                        "$12,000.00",
                        12000.0,
                        "+5.0%",
                        5.0,
                        "$4,500.00",
                        4500.0,
                        "37.5%",
                        37.5,
                        true,
                        Map.of("Revenue", "$12,000.00")
                );

        when(resolutionService.resolve("2026-08-01", "2026-08-31", "2026-07-01", "2026-07-31"))
                .thenReturn(values);

        mvc.perform(post("/api/v1/report/preview-values")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actualStartDate": "2026-08-01",
                                  "actualEndDate": "2026-08-31",
                                  "comparableStartDate": "2026-07-01",
                                  "comparableEndDate": "2026-07-31"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPeriod").value("2026-08-01 to 2026-08-31"))
                .andExpect(jsonPath("$.revenue").value("$12,000.00"))
                .andExpect(jsonPath("$.grossMargin").value("$4,500.00"))
                .andExpect(jsonPath("$.grossMarginPercent").value("37.5%"));
    }

    @Test
    void instantiateWithDownloadModeReturnsDocxFile() throws Exception {
        byte[] dummyTemplate = new byte[]{1, 2, 3};
        byte[] dummyPopulated = new byte[]{4, 5, 6};

        when(templateStore.loadOrCreateMasterTemplate()).thenReturn(dummyTemplate);
        when(resolutionService.resolve(any(), any(), any(), any()))
                .thenReturn(new ReportPlaceholderResolutionService.PlaceholderValues(
                        "p1", "p2", "r", 1.0, "rc", 2.0, "gm", 3.0, "gmp", 4.0, false, Map.of()));
        when(contentControlUpdater.replaceContents(eq(dummyTemplate), any()))
                .thenReturn(new ReportContentControlUpdater.UpdateResult(dummyPopulated, 6));

        mvc.perform(post("/api/v1/report/instantiate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actualStartDate": "2026-08-01",
                                  "actualEndDate": "2026-08-31",
                                  "comparableStartDate": "2026-07-01",
                                  "comparableEndDate": "2026-07-31",
                                  "mode": "download"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"Management_Commentary_20260801_20260831.docx\""))
                .andExpect(content().bytes(dummyPopulated));
    }

    @Test
    void instantiateWithStudioModeSavesToDocumentStoreAndReturnsRedirect() throws Exception {
        byte[] dummyTemplate = new byte[]{1, 2, 3};
        byte[] dummyPopulated = new byte[]{4, 5, 6};

        when(templateStore.loadOrCreateMasterTemplate()).thenReturn(dummyTemplate);
        when(resolutionService.resolve(any(), any(), any(), any()))
                .thenReturn(new ReportPlaceholderResolutionService.PlaceholderValues(
                        "p1", "p2", "r", 1.0, "rc", 2.0, "gm", 3.0, "gmp", 4.0, false, Map.of()));
        when(contentControlUpdater.replaceContents(eq(dummyTemplate), any()))
                .thenReturn(new ReportContentControlUpdater.UpdateResult(dummyPopulated, 6));
        when(documentStore.currentVersion()).thenReturn(15L);

        mvc.perform(post("/api/v1/report/instantiate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actualStartDate": "2026-08-01",
                                  "actualEndDate": "2026-08-31",
                                  "comparableStartDate": "2026-07-01",
                                  "comparableEndDate": "2026-07-31",
                                  "mode": "open_in_studio"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.redirectUrl").value(org.hamcrest.Matchers.startsWith("/report-studio?actualStartDate=2026-08-01")))
                .andExpect(jsonPath("$.documentVersion").value(15));

        verify(documentStore).replaceDefaultDocument(eq(dummyPopulated), any());
        verify(placeholderService).seedOrUpdateConfigurations(eq("default"), eq("2026-08-01 to 2026-08-31"));
        verify(onlyOfficeController).invalidateIssuedDocumentKeys();
    }
}
