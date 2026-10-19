package dev.margintrace.margin_attribution_backend.analysis;

import static dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree.*;
import static dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTreeFixtures.graph;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwEngine;
import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.algorithm.model.PropagationPath;
import dev.margintrace.margin_attribution_backend.analysis.controller.ProductionTreeController;
import dev.margintrace.margin_attribution_backend.analysis.dto.*;
import dev.margintrace.margin_attribution_backend.analysis.service.ProductionTreeService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/** Real JPA -> tree -> serial FGW and HTTP checks in an isolated, rolled-back H2 slice. */
@DataJpaTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.flyway.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:production-tree;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        },
        showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProductionTreeService.class)
class ProductionTreeIntegrationTest {
    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ProductionTreeService service;

    @Autowired
    jakarta.persistence.EntityManager entityManager;

    MockMvc mvc;
    final JsonMapper json = JsonMapper.builder().build();
    static final LocalDate JAN = LocalDate.of(2026, 1, 1), FEB = LocalDate.of(2026, 2, 1);

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ProductionTreeController(service))
                .build();
        insert(1, JAN, "P1", "A", "B", "100", "200", "20");
        insert(2, FEB, "P1", "A", "B", "20", "60", "20");
        insert(3, FEB, "P1", "A", "C", "5", "40", "20");
        insert(4, JAN, "P2", "A", "B", "30", "90", "10");
        insert(5, FEB, "P2", "A", "C", "2", "20", "10");
        // Unreliable planned branches never replace recorded actual issues.
        jdbc.update(
                "INSERT INTO bill_of_material (bom_no,product_no,material_no,material_usage) VALUES ('plan','A','PLANNED_ONLY',999)");
    }

    void insert(
            long id,
            LocalDate date,
            String production,
            String product,
            String material,
            String qty,
            String amount,
            String output) {
        jdbc.update(
                """
                INSERT INTO inventory_usage(id,date,movement_no,order_no,product_no,material_no,
                    material_num,material_total_cost,product_num,product_total_cost)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                id,
                date,
                "M" + id,
                production,
                product,
                material,
                qty == null ? null : new BigDecimal(qty),
                amount == null ? null : new BigDecimal(amount),
                output == null ? null : new BigDecimal(output),
                new BigDecimal("300"));
    }

    Evidence evidence(Scope scope) {
        Map<String, IssueEvidence> issues = new HashMap<>();
        Map<String, OutputEvidence> outputs = new HashMap<>();
        Set<String> ids = new HashSet<>();
        jdbc.query("SELECT id,date,order_no FROM inventory_usage", rs -> {
            String production = rs.getString("order_no");
            ids.add(production);
            issues.put(
                    rs.getLong("id") + "@" + rs.getDate("date").toLocalDate(),
                    new IssueEvidence("kg", MaterialKind.RAW, null, null, null, null));
            outputs.put(
                    production,
                    new OutputEvidence(
                            OutputInterpretation.REPEATED_INSTANCE_TOTAL,
                            OutputInterpretation.REPEATED_INSTANCE_TOTAL,
                            List.of(),
                            "each",
                            true));
        });
        return new Evidence("test-warehouse", scope, ids, issues, outputs, "NZD", "material");
    }

    ActualGraph actualGraph() {
        return graph(
                "test-warehouse",
                Map.of("P1", "A", "P2", "A", "P3", "OTHER", "DOWN", "A", "UP", "S"),
                "A>B",
                "A>C",
                "A>S",
                "S>RAW",
                "OTHER>B");
    }

    ProductionTreeRequest request() {
        return new ProductionTreeRequest(
                List.of("P1", "P2"), null, null, null, null, evidence(Scope.DIRECT_CONSUMPTION), actualGraph());
    }

    Map<String, ProductionComparisonRequest.Embedding> vectors(ProductionTreeResponse trees) {
        Map<String, ProductionComparisonRequest.Embedding> vectors = new HashMap<>();
        for (var tree : trees.trees())
            for (var node : tree.nodes()) {
                double[] values = new double[384];
                values[0] = switch (node.materialId()) {
                    case "A" -> 0;
                    case "B" -> 1;
                    default -> 2;
                };
                vectors.put(node.occurrenceId(), new ProductionComparisonRequest.Embedding("fixture-v1", values));
            }
        return vectors;
    }

    @Test
    void databaseMappingFullPeriodRetrievalUnexpectedMaterialsAndRealEngine() {
        var request = new ProductionTreeRequest(
                null, List.of("A"), JAN, JAN, null, evidence(Scope.DIRECT_CONSUMPTION), actualGraph());
        var trees = service.inspect(request);
        assertEquals(2, trees.trees().size());
        var p1 = trees.trees().get(0);
        assertEquals("P1", p1.productionId());
        assertEquals("300", p1.recordedMaterialAmount());
        assertEquals("20", p1.outputQuantity());
        assertEquals("120", p1.nodes().get(1).quantity());
        assertEquals(List.of("1@2026-01-01", "2@2026-02-01"), p1.nodes().get(1).issueLineIds());
        assertEquals("C", p1.nodes().get(2).materialId());
        var result = service.compare(
                new ProductionComparisonRequest(request, "P1", "P2", vectors(trees), null, List.of(new double[] {
                    1. / 3, 0, 0, 0, 1. / 3, 0, 0, 0, 1. / 3
                })));
        assertTrue(result.converged());
        assertEquals("JAVA_SERIAL_EXACT_SIMPLEX", result.result().execution);
        assertEquals(0, result.result().starts.get(0).costs.fgw(), 1e-8);
        assertEquals("260", result.result().left.nodes().get(1).amount());
        assertEquals("90", result.result().right.nodes().get(1).amount());
        assertTrue(result.result().right.nodes().get(1).fifoAllocationIds().isEmpty());
    }

    @Test
    void identityFailuresAndCoverageAreExplicit() {
        insert(6, JAN, "AMBIG", "A", "B", "1", "1", "1");
        insert(7, JAN, "AMBIG", "OTHER", "B", "1", "1", "1");
        var e = evidence(Scope.DIRECT_CONSUMPTION);
        e = new Evidence(
                e.namespace(), e.scope(), Set.of(), e.issues(), e.outputs(), e.currency(), e.materialCostScope());
        var request = new ProductionTreeRequest(
                Arrays.asList("P1", "P1", "AMBIG", "absent", "", null), null, null, null, null, e, actualGraph());
        var trees = service.inspect(request);
        assertEquals(1, trees.trees().size());
        assertTrue(trees.trees().get(0).statuses().contains("INCOMPLETE_IMPORTED_COVERAGE"));
        assertTrue(trees.identities().stream().anyMatch(i -> i.statuses().contains("AMBIGUOUS_PRODUCTION_PRODUCT")));
        assertEquals(
                3,
                trees.identities().stream()
                        .filter(i -> i.statuses().contains("MISSING_PRODUCTION_ID"))
                        .count());
        assertNull(service.compare(new ProductionComparisonRequest(request, "P1", "absent", Map.of(), null, null))
                .result());
    }

    @Test
    void reuseAbnormalPathsAndFollowRecordedRelationshipsRetainsAllReferences() {
        var path = new ReconciliationPathEntry(
                List.of(new Node("B", BigDecimal.ONE)),
                List.of(),
                PropagationPath.EndReason.THRESHOLD_EXCEEDED,
                BigDecimal.TEN);
        var req = new ProductionTreeRequest(
                null, null, JAN, JAN, List.of(path, path), evidence(Scope.DIRECT_CONSUMPTION), actualGraph());
        var trees = service.inspect(req);
        assertEquals(2, trees.trees().size());
        assertEquals(
                List.of("abnormal-path:0", "abnormal-path:1"),
                trees.trees().get(0).selectionReferences());
    }

    @Test
    void databaseUpstreamEvidenceProjectsOnlyDeclaredShare() {
        insert(6, JAN, "DOWN", "A", "S", "2", "50", "1");
        insert(7, FEB, "UP", "S", "RAW", "100", "200", "10");
        var original = evidence(Scope.FULL_LINEAGE);
        var issues = new HashMap<>(original.issues());
        issues.put(
                "6@2026-01-01",
                new IssueEvidence("each", MaterialKind.INTERMEDIATE, null, "UP", new BigDecimal("0.2"), null));
        var e = new Evidence(
                original.namespace(),
                original.scope(),
                original.completeProductionIds(),
                issues,
                original.outputs(),
                "NZD",
                "material");
        var trees = service.inspect(new ProductionTreeRequest(List.of("DOWN"), null, JAN, JAN, null, e, actualGraph()));
        var tree = trees.trees().get(0);
        assertTrue(tree.lineageComplete());
        assertEquals("50", tree.recordedMaterialAmount());
        assertEquals("20", tree.nodes().get(2).quantity());
        assertEquals("40", tree.nodes().get(2).amount());
        assertEquals("UP", tree.nodes().get(2).productionId());
        assertEquals(List.of("7@2026-02-01"), tree.nodes().get(2).issueLineIds());
    }

    @Test
    void endpointInspectionAndMissingEmbeddingResponse() throws Exception {
        String body = json.writeValueAsString(request());
        mvc.perform(post("/api/v1/analysis/production-trees/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trees.length()").value(2))
                .andExpect(jsonPath("$.trees[0].recordedMaterialAmount").value("300"))
                .andExpect(jsonPath("$.trees[0].nodes[1].issueLineIds.length()").value(2));
        var comparison = new ProductionComparisonRequest(request(), "P1", "P2", Map.of(), null, null);
        mvc.perform(post("/api/v1/analysis/production-trees/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(comparison)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statuses[0]").value("MISSING_EMBEDDINGS"))
                .andExpect(jsonPath("$.missingVectors.length()").value(6))
                .andExpect(jsonPath("$.result").isEmpty());
    }

    @Test
    void endpointRealEngineAndInvalidArguments() throws Exception {
        var req = request();
        var trees = service.inspect(req);
        var comparison = new ProductionComparisonRequest(
                req, "P1", "P2", vectors(trees), null, List.of(new double[] {1. / 3, 0, 0, 0, 1. / 3, 0, 0, 0, 1. / 3
                }));
        mvc.perform(post("/api/v1/analysis/production-trees/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(comparison)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.converged").value(true))
                .andExpect(jsonPath("$.result.starts[0].costs.fgw").value(0))
                .andExpect(jsonPath("$.result.left.nodes[1].productionOrderId").value("P1"))
                .andExpect(jsonPath("$.result.left.nodes[1].fifoAllocationIds").isEmpty());
        mvc.perform(post("/api/v1/analysis/production-trees/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
        var config = new FgwEngine.Config();
        config.alpha = 2;
        mvc.perform(post("/api/v1/analysis/production-trees/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new ProductionComparisonRequest(req, "P1", "P2", vectors(trees), config, null))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/analysis/production-trees/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ProductionComparisonRequest(
                                req, "P1", "P2", vectors(trees), null, List.of(new double[9])))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void endpointIncompatibleProductsAndNumericalFailureAreExplicit() throws Exception {
        insert(6, JAN, "P3", "OTHER", "B", "1", "1", "1");
        var req = new ProductionTreeRequest(
                List.of("P1", "P3"), null, null, null, null, evidence(Scope.DIRECT_CONSUMPTION), actualGraph());
        var trees = service.inspect(req);
        mvc.perform(post("/api/v1/analysis/production-trees/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new ProductionComparisonRequest(req, "P1", "P3", vectors(trees), null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statuses[0]").value("INCOMPATIBLE_PRODUCT"))
                .andExpect(jsonPath("$.result").isEmpty());
        var normal = request();
        var vectors = vectors(service.inspect(normal));
        vectors.values().iterator().next().values()[0] = 1e308;
        mvc.perform(post("/api/v1/analysis/production-trees/compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new ProductionComparisonRequest(normal, "P1", "P2", vectors, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.converged").value(false))
                .andExpect(jsonPath("$.result.starts[0].status").value("NUMERICAL_FAILURE"));
    }

    ProductionTreeRequest costRequest(boolean missingLabor, boolean explicitZero) {
        insert(6, FEB, "P_S", "S", "R", "10", "100", "10");
        insert(7, JAN, "P_B", "B", "R", "99", "990", "1");
        insert(8, JAN, "P_A", "A", "S", "2", "30", "2");
        insert(9, JAN, "P_C", "C", "S", "8", "96", "8");
        jdbc.update("UPDATE inventory_usage SET product_total_cost=120 WHERE order_no='P_S'");
        jdbc.update("UPDATE inventory_usage SET product_total_cost=40 WHERE order_no='P_A'");
        Evidence base = evidence(Scope.FULL_LINEAGE);
        Map<String, IssueEvidence> issues = new HashMap<>(base.issues());
        issues.put(
                "8@2026-01-01",
                new IssueEvidence("each", MaterialKind.INTERMEDIATE, null, "P_S", new BigDecimal("0.2"), null));
        Map<String, OutputEvidence> outputs = new HashMap<>(base.outputs());
        for (String id : List.of("P_A", "P_S"))
            outputs.put(
                    id,
                    new OutputEvidence(
                            OutputInterpretation.REPEATED_INSTANCE_TOTAL,
                            OutputInterpretation.REPEATED_INSTANCE_TOTAL,
                            List.of(),
                            "each",
                            true,
                            "NZD",
                            "manufacturing"));
        BigDecimal labor = missingLabor ? null : new BigDecimal(explicitZero ? "0" : "4");
        BigDecimal overhead = new BigDecimal(explicitZero ? "0" : "6");
        Evidence e = new Evidence(
                base.namespace(),
                base.scope(),
                base.completeProductionIds(),
                issues,
                outputs,
                "NZD",
                "manufacturing",
                "manufacturing",
                List.of(
                        new ProcessingSource(
                                "pool-S", "P_S", "NZD", "manufacturing", new BigDecimal("12"), new BigDecimal("8")),
                        new ProcessingSource("pool-A", "P_A", "NZD", "manufacturing", labor, overhead)),
                List.of(
                        new ProcessingAllocation(
                                "edge-S",
                                "pool-S",
                                "P_S",
                                null,
                                List.of("6@2026-02-01"),
                                "NZD",
                                "manufacturing",
                                "explicit edge",
                                new BigDecimal("12"),
                                new BigDecimal("8")),
                        new ProcessingAllocation(
                                "edge-A",
                                "pool-A",
                                "P_A",
                                null,
                                List.of("8@2026-01-01"),
                                "NZD",
                                "manufacturing",
                                "explicit edge",
                                labor,
                                overhead)));
        return new ProductionTreeRequest(
                List.of("P_A"), null, JAN, JAN, null, e, graph("test-warehouse", Map.of("P_A", "A"), "A>S", "S>R"));
    }

    @Test
    void twoPassFixedCostFixtureRunsThroughJpaServiceAndHttp() throws Exception {
        ProductionTreeRequest req = costRequest(false, false);
        ProductionTree tree = service.inspect(req).trees().get(0);
        assertEquals("40", tree.calculatedTotalCost());
        assertEquals("30", tree.recordedMaterialAmount());
        assertEquals("24", tree.nodes().get(1).totalCost());
        assertEquals("120", tree.nodes().get(1).sourceTotalCost());
        assertEquals("6", tree.nodes().get(1).sourceValuationDifference());
        assertEquals(List.of("6@2026-02-01"), tree.nodes().get(2).issueLineIds());
        assertEquals("2.4", tree.edges().get(1).laborCost());
        assertEquals("1.6", tree.edges().get(1).overheadCost());
        assertEquals(4, tree.sourceIssues().size());
        assertEquals(
                2, tree.issueDecisions().stream().filter(d -> !d.accepted()).count());
        mvc.perform(post("/api/v1/analysis/production-trees/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trees[0].calculatedTotalCost").value("40"))
                .andExpect(jsonPath("$.trees[0].knownTotalCost").value("40"))
                .andExpect(jsonPath("$.trees[0].costComplete").value(true))
                .andExpect(jsonPath("$.trees[0].recordedMaterialAmount").value("30"))
                .andExpect(jsonPath("$.trees[0].totalOutputCostDifference").value("0"))
                .andExpect(jsonPath("$.trees[0].outputCostDifference").value("10"))
                .andExpect(jsonPath("$.trees[0].nodes[1].sourceTotalCost").value("120"))
                .andExpect(
                        jsonPath("$.trees[0].nodes[1].projectedSourceTotalCost").value("24"))
                .andExpect(jsonPath("$.trees[0].nodes[1].sourceValuationDifference")
                        .value("6"))
                .andExpect(jsonPath("$.trees[0].edges[1].laborCost").value("2.4"))
                .andExpect(jsonPath("$.trees[0].edges[1].overheadCost").value("1.6"))
                .andExpect(result -> assertEquals(
                        "[\"A\",\"S\",\"R\"]",
                        json.readTree(result.getResponse().getContentAsString())
                                .get("trees")
                                .get(0)
                                .get("topDownScope")
                                .get("paths")
                                .get(0)
                                .toString()));
    }

    @Test
    void apiMissingLaborKeepsKnownSubtotalAndExplicitZeroRemainsComplete() throws Exception {
        var req = costRequest(true, false);
        mvc.perform(post("/api/v1/analysis/production-trees/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trees[0].calculatedTotalCost").isEmpty())
                .andExpect(jsonPath("$.trees[0].knownTotalCost").value("36"))
                .andExpect(jsonPath("$.trees[0].recordedMaterialAmount").value("30"))
                .andExpect(jsonPath("$.trees[0].costComplete").value(false))
                .andExpect(jsonPath("$.trees[0].totalOutputCostDifference").isEmpty())
                .andExpect(jsonPath("$.trees[0].edges[0].laborCost").isEmpty());
        Evidence e = req.evidence();
        List<ProcessingSource> sources = new ArrayList<>(e.processingSources());
        sources.set(1, new ProcessingSource("pool-A", "P_A", "NZD", "manufacturing", BigDecimal.ZERO, BigDecimal.ZERO));
        List<ProcessingAllocation> allocations = new ArrayList<>(e.processingAllocations());
        allocations.set(
                1,
                new ProcessingAllocation(
                        "edge-A",
                        "pool-A",
                        "P_A",
                        null,
                        List.of("8@2026-01-01"),
                        "NZD",
                        "manufacturing",
                        "explicit edge",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO));
        Evidence zero = new Evidence(
                e.namespace(),
                e.scope(),
                e.completeProductionIds(),
                e.issues(),
                e.outputs(),
                e.currency(),
                e.materialCostScope(),
                e.totalCostScope(),
                sources,
                allocations);
        mvc.perform(post("/api/v1/analysis/production-trees/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ProductionTreeRequest(
                                req.productionIds(), null, JAN, JAN, null, zero, req.actualGraph()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trees[0].calculatedTotalCost").value("30"))
                .andExpect(jsonPath("$.trees[0].costComplete").value(true))
                .andExpect(jsonPath("$.trees[0].laborCost").value("0"));
    }

    @Test
    void apiOriginalGroupingMissingCostsAndExplicitZeroAllocations() throws Exception {
        var req = request();
        Evidence e = req.evidence();
        mvc.perform(post("/api/v1/analysis/production-trees/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trees[0].knownTotalCost").value("300"))
                .andExpect(jsonPath("$.trees[1].knownTotalCost").value("110"))
                .andExpect(jsonPath("$.trees[0].costComplete").value(false))
                .andExpect(jsonPath("$.trees[0].calculatedTotalCost").isEmpty());
        List<ProcessingSource> sources = new ArrayList<>();
        List<ProcessingAllocation> allocations = new ArrayList<>();
        for (var tree : service.inspect(req).trees()) {
            sources.add(new ProcessingSource(
                    tree.productionId(), tree.productionId(), "NZD", "material", BigDecimal.ZERO, BigDecimal.ZERO));
            for (var edge : tree.edges())
                allocations.add(new ProcessingAllocation(
                        tree.productionId() + ":" + edge.materialIndex(),
                        tree.productionId(),
                        tree.productionId(),
                        null,
                        edge.issueLineIds(),
                        "NZD",
                        "material",
                        "explicit zero processing",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO));
        }
        Evidence zero = new Evidence(
                e.namespace(),
                e.scope(),
                e.completeProductionIds(),
                e.issues(),
                e.outputs(),
                e.currency(),
                e.materialCostScope(),
                "material",
                sources,
                allocations);
        mvc.perform(post("/api/v1/analysis/production-trees/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ProductionTreeRequest(
                                req.productionIds(), null, null, null, null, zero, req.actualGraph()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trees[0].calculatedTotalCost").value("300"))
                .andExpect(jsonPath("$.trees[1].calculatedTotalCost").value("110"))
                .andExpect(jsonPath("$.trees[0].costComplete").value(true));
    }

    @Test
    void apiRequiresInputScopeAndDoesNotAcceptFinalOutputCsrAsFallback() throws Exception {
        var req = request();
        mvc.perform(post("/api/v1/analysis/production-trees/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ProductionTreeRequest(
                                req.productionIds(), null, null, null, null, req.evidence(), null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("MISSING_PRECEDING_ACTUAL_GRAPH"));
        ActualGraph g = req.actualGraph();
        ActualGraph partial = new ActualGraph(
                g.graphId(),
                g.namespace(),
                g.direction(),
                true,
                g.nodes(),
                g.rowOffsets(),
                g.colIndices(),
                g.edgeIds(),
                g.roots(),
                true,
                JAN,
                JAN);
        ProductionTree tree = service.inspect(
                        new ProductionTreeRequest(List.of("P1"), null, JAN, JAN, null, req.evidence(), partial))
                .trees()
                .get(0);
        assertEquals("300", tree.recordedMaterialAmount());
        assertFalse(tree.directComplete());
        assertTrue(tree.statuses().contains("INCOMPLETE_GRAPH_PERIOD_COVERAGE"));
    }

    @Test
    void apiDecimalAdditionProjectionAndMissingMaterialStayExactAndPartial() throws Exception {
        var req = costRequest(false, false);
        insert(10, FEB, "P_S", "S", "R", "0.1", "0.1", "10");
        insert(11, FEB, "P_S", "S", "R", "0.2", "0.2", "10");
        jdbc.update("UPDATE inventory_usage SET product_total_cost=120.3 WHERE order_no='P_S'");
        Evidence base = req.evidence();
        Map<String, IssueEvidence> issues = new HashMap<>(base.issues());
        for (String id : List.of("10@2026-02-01", "11@2026-02-01"))
            issues.put(id, new IssueEvidence("kg", MaterialKind.RAW, null, null, null, null));
        List<ProcessingAllocation> allocations = new ArrayList<>(base.processingAllocations());
        allocations.set(
                0,
                new ProcessingAllocation(
                        "edge-S",
                        "pool-S",
                        "P_S",
                        null,
                        List.of("6@2026-02-01", "10@2026-02-01", "11@2026-02-01"),
                        "NZD",
                        "manufacturing",
                        "explicit grouped edge",
                        new BigDecimal("12"),
                        new BigDecimal("8")));
        Evidence e = new Evidence(
                base.namespace(),
                base.scope(),
                base.completeProductionIds(),
                issues,
                base.outputs(),
                base.currency(),
                base.materialCostScope(),
                base.totalCostScope(),
                base.processingSources(),
                allocations);
        var exact = new ProductionTreeRequest(req.productionIds(), null, null, null, null, e, req.actualGraph());
        mvc.perform(post("/api/v1/analysis/production-trees/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(exact)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trees[0].nodes[2].quantity").value("2.06"))
                .andExpect(jsonPath("$.trees[0].nodes[2].amount").value("20.06"))
                .andExpect(jsonPath("$.trees[0].nodes[1].totalCost").value("24.06"))
                .andExpect(jsonPath("$.trees[0].nodes[1].sourceTotalCost").value("120.3"))
                .andExpect(jsonPath("$.trees[0].nodes[1].sourceValuationDifference")
                        .value("5.94"))
                .andExpect(jsonPath("$.trees[0].calculatedTotalCost").value("40"));
        jdbc.update("UPDATE inventory_usage SET material_total_cost=NULL WHERE id=11");
        entityManager.clear(); // JDBC fixture changes must replace already loaded JPA entities.
        mvc.perform(post("/api/v1/analysis/production-trees/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(exact)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trees[0].nodes[2].amount").value("20.02"))
                .andExpect(jsonPath("$.trees[0].nodes[1].knownTotalCost").value("24.02"))
                .andExpect(jsonPath("$.trees[0].nodes[1].totalCost").isEmpty())
                .andExpect(jsonPath("$.trees[0].nodes[1].sourceValuationDifference")
                        .isEmpty())
                .andExpect(jsonPath("$.trees[0].calculatedTotalCost").value("40"))
                .andExpect(
                        jsonPath("$.trees[0].edges[0].materialAmountComplete").value(true))
                .andExpect(
                        jsonPath("$.trees[0].edges[1].materialAmountComplete").value(false));
        jdbc.update("UPDATE inventory_usage SET material_num=NULL WHERE id=8");
        entityManager.clear();
        var incomplete = service.inspect(exact).trees().get(0);
        assertEquals("40", incomplete.knownTotalCost());
        assertNull(incomplete.calculatedTotalCost());
        assertEquals("30", incomplete.recordedMaterialAmount());
        assertFalse(incomplete.costComplete());
    }
}
