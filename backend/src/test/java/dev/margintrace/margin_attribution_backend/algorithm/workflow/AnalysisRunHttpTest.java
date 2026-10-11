package dev.margintrace.margin_attribution_backend.algorithm.workflow;

import dev.margintrace.margin_attribution_backend.analysis.controller.AnalysisRunController;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisRunResponse;
import dev.margintrace.margin_attribution_backend.analysis.service.AnalysisRunService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static dev.margintrace.margin_attribution_backend.algorithm.workflow.AnalysisFixtures.*;

/** HTTP adaptation uses the same outcome; no local server, warehouse or separate computation is involved. */
class AnalysisRunHttpTest {
    @Test void progressWritesIncrementallyAndLeavesResponseOpen() throws Exception {
        class ResponseStream extends java.io.ByteArrayOutputStream {
            boolean closed;
            int largestWrite;
            @Override public void write(byte[] bytes, int offset, int length) {
                assertFalse(closed);
                largestWrite = Math.max(largestWrite, length);
                super.write(bytes, offset, length);
            }
            @Override public void close() { closed = true; }
        }
        var output = new ResponseStream();
        var event = new AnalysisProgressListener.Event(java.util.UUID.randomUUID(), 1,
                "production-trees", java.util.Map.of("reason", "evidence".repeat(100_000),
                "amount", new java.math.BigDecimal("123.4500")));
        AnalysisRunResponse.writeEvent(output, event);
        AnalysisRunResponse.writeEvent(output, java.util.Map.of("type", "completed"));
        assertFalse(output.closed);
        assertTrue(output.largestWrite < 64 * 1024, "Events must be written in bounded chunks");
        String[] lines = output.toString(java.nio.charset.StandardCharsets.UTF_8).split("\n");
        assertEquals(2, lines.length);
        var mapper = new ObjectMapper();
        var first = mapper.readTree(lines[0]);
        assertEquals("evidence".repeat(100_000), first.path("data").path("reason").asText());
        assertEquals("123.4500", first.path("data").path("amount").asText());
        assertEquals("completed", mapper.readTree(lines[1]).path("type").asText());
    }

    @Test void newEndpointPreservesWorkflowIdentityAndDecimalText() throws Exception {
        var expected=AnalysisWorkflowTest.workflow(new RecordingProvider()).run(command());
        var service=new AnalysisRunService(null,"","","",60){
            @Override public AnalysisOutcome run(AnalysisCommand command,AnalysisProgressListener progress){return expected;}
        };
        var mvc=MockMvcBuilders.standaloneSetup(new AnalysisRunController(service)).build();var mapper=new ObjectMapper();
        var response=mvc.perform(post("/api/v1/analysis/run").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(command())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analysisId").value(expected.analysisId().toString()))
                .andExpect(jsonPath("$.planned").value(1)).andExpect(jsonPath("$.executed").value(1))
                .andExpect(jsonPath("$.actual.graph.nodes[1].cost").value("120"))
                .andExpect(jsonPath("$.actual.records.issues[0].amount").value("130"))
                .andExpect(jsonPath("$.selection.trees[1].calculatedTotalCost").value("270")).andReturn();
        assertEquals(mapper.readTree(mapper.writeValueAsString(AnalysisRunResponse.json(expected))),mapper.readTree(response.getResponse().getContentAsString()));
        var invalidService=new AnalysisRunService(null,"","","",60){
            @Override public AnalysisOutcome run(AnalysisCommand command,AnalysisProgressListener progress){throw new IllegalArgumentException("Invalid threshold");}
        };
        MockMvcBuilders.standaloneSetup(new AnalysisRunController(invalidService)).build().perform(post("/api/v1/analysis/run").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(command())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid threshold"));
    }
}
