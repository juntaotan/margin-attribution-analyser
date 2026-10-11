# Independent analysis workflow design

This document proposes how to connect existing algorithms. It does not implement the workflow or establish runtime/device correctness. Repository inspection was performed on 11 October 2026. Material comparison is interpreted as the existing production-instance FGW comparison; decimal consumption/cost evidence remains a separate result.

## Existing entry points and modules

Java paths below are relative to `backend/src/main/java/dev/margintrace/margin_attribution_backend/`.

| Responsibility | Existing implementation | Current connection |
| --- | --- | --- |
| Period analysis entry | `analysis/controller/AnalysisController.java`, `analysis/service/Analyser.java` | `/api/v1/analysis/reconcile-periods` and `/reconcile-periods/stream` build two CSR graphs and reconcile them. |
| CSR construction | `algorithm/TreeBuilder/AttributionWorkflow.java`, `ReadDataHandler.java`, `EdgeBuildHandler.java`, `CsrGraphHandler.java` | Reads sold products and period inventory usage; aggregates nodes by inventory ID, with a synthetic `__COGS__` root. |
| Graph contracts | `algorithm/model/CsrGraph.java`, `Node.java`, `GraphEdge.java`, `PropagationPath.java` | Period graph positions and exact decimal quantities/costs; no production-instance occurrence identity. |
| Threshold preparation | `algorithm/BOMFrontier/BomGraphPreparer.java` | Aligns actual/comparable costs by inventory ID and selects material-side leaves using `leafThreshold`. |
| Upward pruning | `algorithm/BOMFrontier/BomUpwardEngine.java` | Selects eligible ancestors using `stopThreshold`; selected children stop propagation and block shared ancestors. Values are supplied differences, not accumulated during traversal. |
| Abnormal path reconstruction | `algorithm/BOMFrontier/BomFrontierReconciler.java` | Reconstructs material-to-selected-boundary paths using the original actual graph. |
| Production selection/loading | `analysis/service/ProductionTreeService.java` | Accepts `abnormalPaths`, follows recorded material-to-product relationships and selects `order_no`; loads all available rows of selected IDs. |
| Actual instance tree construction | `algorithm/ProductionTree/ProductionTreeBuilder.java`, `ProductionTree.java` | Builds separate occurrence trees for production IDs with issue/source evidence and declared scope. |
| Eligibility and solver adaptation | `algorithm/ProductionTree/ProductionTreeFgwAdapter.java` | Validates scope, completeness and occurrence embeddings, then invokes Java FGW. Currently imports analysis DTOs. |
| Pairwise material/structure comparison | `algorithm/FGW/FgwEngine.java` | Serial Java FP64 balanced FGW, currently invoked by `/api/v1/analysis/production-trees/compare`. |
| Optional statistical scoring | `algorithm/LOF/LofEngine.java` | Four-feature LOF calculation; no caller in current Java business code or frontend. Tests still exercise it. |

The frontend entry in `frontend/src/components/DualBom/DualBomAnalysisPage.tsx` calls `/api/v1/analysis/reconcile-periods/stream` with four dates and two thresholds. It receives `graph` and `diff` events. It does not invoke production-tree comparison.

`AnalysisRequest` also contains `company` and `targets`, but `/trace` derives its targets from sales rather than this field; `company` is not passed into its workflow. These fields must not be represented as existing warehouse filters.

### Native implementations

| Stage | CUDA | OpenCL | Java backend binding |
| --- | --- | --- | --- |
| Period CSR | `cuda/include/tree_builder/`, `cuda/src/` standalone library | No corresponding integrated builder identified | No CUDA binding identified. Preserve current Java loading/CSR construction initially. |
| Frontier pruning | `backend/src/main/resources/cuda/bom_upward_engine.cu` and `.cuh` | `backend/src/main/resources/opencl/bom_upward_engine.cpp`, `.hpp`, `bom_upward.cl` | Current reconciler constructs the Java engine directly. |
| FGW | `backend/src/main/resources/cuda/fgw_engine.cu`, `.cuh`, `fgw.cu` | `backend/src/main/resources/opencl/fgw_engine.cpp`, `.hpp`, `fgw.cl` | Current production adapter constructs a Java session directly. |
| LOF | `backend/src/main/resources/cuda/lof_calculate.cu`, `lof_engine.cuh` | `backend/src/main/resources/opencl/lof_engine.cpp`, `.hpp`, `lof_calculate.cl` | No business integration identified. |

Native source files and mathematical parity do not provide a Java-callable bridge. CUDA CMake now has dedicated FGW options and targets; this inspection did not build or execute them. Device availability and native validation are unverified in this task.

## Proposed independent pipeline

```text
AnalysisController / another Java caller
  -> AnalysisWorkflow.run(command, progressListener)
     -> build actual and comparable period CSR snapshots
     -> prepare thresholds and prune -> abnormal branch evidence
     -> select receiving products and production IDs in both periods
     -> load full instances by order_no -> build actual occurrence trees
     -> enumerate all unique same-product production-ID pairs
     -> validate completeness and occurrence embeddings for each pair
     -> FGW -> join correspondence to source evidence
     -> return paths, selected instances, comparisons and stage statuses
```

The product-facing description can remain “CSR -> abnormal branches -> material comparison”. Instance selection/tree construction is the necessary adapter between the latter two stages.

Do not feed a period `CsrGraph` directly to FGW as an actual production instance. It has already merged inventory IDs across production records, and the synthetic COGS node is not a receiving production. Retain period graphs for discovery; rebuild occurrence trees from original records for accepted comparisons. Preserve branch/node/edge mappings throughout.

### Package arrangement

Keep existing package names and computational modules. Add small boundary modules instead of moving all current code:

```text
algorithm/
  workflow/
    AnalysisWorkflow                # Single reusable Java orchestration entry
    AnalysisCommand                 # Domain request, without HTTP/JPA types
    AnalysisOutcome                 # Domain evidence, stage statuses, comparisons
    AnalysisProgressListener        # Optional progress callback
    ProductionPairSelector          # All unique same-product order_no pairs
  port/
    PeriodGraphProvider             # Load/build each requested period graph
    ProductionDataProvider          # Select IDs and load original issue records
    EmbeddingProvider               # Resolve supplied/versioned content vectors
  execution/
    ExecutionPolicy                 # Caller-supplied useGpu/isNvidia and fallback policy
    BackendRegistry                 # Registered engine adapters; no vendor discovery
    BackendResolver                 # CPU/CUDA/OpenCL from caller-supplied flags
    ExecutionReport                 # Requested/used backend and fallback evidence
  TreeBuilder/                      # Existing period graph builders
  BOMFrontier/
    FrontierEngine                  # Small abstraction for existing run contract
    BomUpwardEngine                 # Existing Java implementation
    BomFrontierReconciler            # Inject engine; keep path reconstruction shared
  ProductionTree/                   # Existing builder, semantic gate and mappings
  FGW/
    FgwComparator                   # Domain comparison/session boundary
    FgwEngine                       # Existing Java solver
  LOF/                              # Optional standalone scorer
  model/                            # Existing shared graph/path contracts

analysis/
  controller/                       # HTTP validation and JSON/NDJSON adaptation
  dto/                              # Public API compatibility
  service/                          # Spring wiring and read-only warehouse adapters

infrastructure/acceleration/         # Proposed JNI wrappers and native resource lifetime
```

The workflow depends on domain contracts and ports. Spring services implement repository-facing ports; native wrappers implement engine boundaries. The workflow must not import controllers, HTTP DTOs, repositories or JNI details. Existing `AttributionWorkflow` accesses repositories and can be wrapped by a `PeriodGraphProvider` initially; no large TreeBuilder rewrite is needed.

When extracting the boundary, replace `ProductionTreeFgwAdapter`'s imports of `analysis.dto.*` with domain inputs/results, with the old service translating its existing DTOs. Extract the current selection rules from `ProductionTreeService` behind a provider that retains selection references. Both old endpoints and the new workflow can reuse it without `algorithm -> analysis -> algorithm` coupling.

Keep database orchestration and pair scheduling in Java. Native engines receive arrays, mappings and numerical configuration; they do not query the warehouse or decide business eligibility. For Java 21, a JNI boundary is a concrete implementation option; native packaging and ABI/error handling still require implementation and tests. No new dependency is required for this design task.

### Proposed callable contracts

Conceptual signatures, not implemented classes:

```java
AnalysisOutcome run(AnalysisCommand command);
AnalysisOutcome run(AnalysisCommand command, AnalysisProgressListener progress);
```

Use immutable per-run state. Treat submitted arrays as immutable, retaining owned copies at external/native boundaries. Never share a serial Java FGW session across request threads. A synchronous first implementation should create/use/close comparison resources within the calling run; request cancellation and native cleanup must be explicit.

## External API

Add `POST /api/v1/analysis/run` and, when streaming is implemented, `/run/stream`. Keep existing reconcile/trace/tree endpoints compatible and make them reuse the extracted stages. Streaming is a presentation adapter around the same workflow, not a second pipeline.

Preserve the current period fields:

```json
{
  "actualStartDate": "2026-09-01",
  "actualEndDate": "2026-09-30",
  "comparableStartDate": "2026-08-01",
  "comparableEndDate": "2026-08-31",
  "leafThreshold": "100.000000",
  "stopThreshold": "500.000000",
  "execution": {
    "useGpu": true,
    "isNvidia": true,
    "deviceId": null,
    "allowCpuFallback": true
  },
  "comparison": {
    "mode": "ALL_PAIRS",
    "scope": "DIRECT_CONSUMPTION",
    "evidence": {},
    "embeddings": {},
    "config": {"alpha": 0.8}
  }
}
```

This example illustrates the proposed field layout. Empty evidence/vectors produce explicit missing-input statuses, not an accepted FGW calculation. Evidence and embedding values retain the semantics documented in [Production Tree Java API](<Production Tree Java API.md>); FGW configuration retains existing defaults and definitions. Thresholds should be decimal strings in the new API to preserve exact client values; old numeric request fields remain accepted by compatibility endpoints.

If product filtering is added, document and implement it explicitly for both graphs and candidate selection; do not expose an ignored `targets` field. Do not add `company` as a supposed organisation filter: the current warehouse has no organisation column. The evidence namespace labels a scoped dataset and is not row-level isolation.

### Candidate and pair selection

1. Preserve abnormal path references, graph role, selected boundary node and original graph positions. Exclude synthetic `__COGS__` from production identity selection while retaining it in discovery evidence.
2. Follow actual recorded relationships to receiving products. Deduplicate candidates by namespace and production ID, retaining all branch references. A candidate is not a proven abnormal instance.
3. Select candidate production IDs in the actual period. For the same receiving products, discover candidates independently in the comparable period; they do not need their own abnormal paths. Combine both sets and deduplicate scoped production IDs, retaining period membership and all selection evidence. These are the objects for all-pairs comparison, not a fixed-baseline or exclusively cross-period comparison.
4. Load all available records of every selected production ID, including outside selection dates. Build direct/full-lineage trees according to supplied evidence. Never infer upstream production links, FIFO allocation or source shares.
5. Group candidates by namespace and receiving product. Within each group, deterministically order production IDs and enumerate every pair `(i,j)` with `i < j`. Never compare an ID with itself or schedule both `(i,j)` and `(j,i)`. A group of `n` IDs has `n * (n - 1) / 2` planned comparisons: 100 eligible IDs of one product produce 4,950 FGW comparisons. Include actual-to-actual, comparable-to-comparable and cross-period pairs. Do not require caller-supplied pairs or choose a fixed baseline. A group with fewer than two IDs returns `INSUFFICIENT_PRODUCTION_INSTANCES`.
6. Check each planned pair for same-product, different-production-ID, namespace, topology-scope and completeness eligibility before numerical execution. Return per-pair rejection evidence rather than silently dropping invalid pairs. Planned, executed, rejected and numerically failed counts must be reported separately; 4,950 planned pairs are not 4,950 successful solves when inputs are incomplete.
7. Supply each occurrence's own finite 384-dimensional content vector with a compatible version, including roots. A configured provider may resolve vectors by material/version and map them to occurrences; missing vectors remain missing. Generation is a separate capability.

If no paths are found, short-circuit instance selection/comparison and report `NO_ABNORMAL_BRANCHES`. In particular, do not call the current `ProductionTreeService.inspect` with only dates and an empty `abnormalPaths` list: that combination selects all instances in the period. Missing comparison data should retain discovery results with `COMPARISON_INPUT_REQUIRED`. Missing costs are explicit incomplete discovery evidence, not proof of normality.

### Result and progress contracts

The result should include `analysisId`, final status, both graph snapshots, abnormal paths, production selection/source references, inspected trees, deterministically ordered planned/executed/rejected pairs and their counts, per-pair FGW outputs and stage execution reports. FGW output retains correspondence, material/structure/FGW costs, convergence, numerical diagnostics and occurrence/source mappings. Decimal financial values remain strings in new result fields.

Proposed streaming events are `graph`, `branches`, `production-trees`, `comparison`, `completed` and `error`, all sharing the run ID and increasing sequence number. Existing `/reconcile-periods/stream` retains its `graph`/`diff` protocol. Controllers translate progress events without rebuilding graphs or rerunning the solver. Source evidence must come from the same loaded run snapshots; clients join comparison occurrences to tree/issue evidence using IDs, not inventory IDs alone.

Use an isolated, read-only consistent database snapshot for source loading, then retain immutable loaded inputs for computation. Do not keep a database transaction open while waiting for new caller evidence. A run with missing evidence returns its partial outcome; a subsequent submission reloads current data and identifies its new run. Persistent/resumable runs would need a separate snapshot/version contract.

### What the current FGW implementation returns

`FgwEngine.Result` describes one left/right production-tree comparison. Its central output is a node correspondence matrix and the objective costs under that correspondence, not a monetary variance or a ready-made anomaly diagnosis.

| Field | Interpretation |
| --- | --- |
| `rows`, `columns` | Left and right tree node counts, including roots. |
| `left`, `right` | Product/instance/root identity and ordered node source mappings. Each node includes occurrence, material, receiving production, issue IDs and decimal quantity/amount text. |
| `p`, `q` | Fixed node weights: `1/rows` and `1/columns`, summing to one on each side. |
| `starts`, `bestStart` | Results for each initial transport matrix and the selected lowest-cost non-numerical-failure start, when one exists. Check its status; selection does not imply convergence. |
| `starts[k].correspondence` | Flattened row-major transport matrix `P`; entry `(i,j)` is at `i * columns + j`. |
| `starts[k].costs.material` | Transport-weighted squared distance between the nodes' content embeddings. |
| `starts[k].costs.structure` | Transport-weighted squared differences of within-tree shortest-path distances. |
| `starts[k].costs.fgw` | `(1-alpha) * material + alpha * structure`; default `alpha=0.8`. |
| `starts[k].iterations`, `finalGap`, `status`, `diagnostic` | Applied update count, final Frank–Wolfe gap and numerical/stopping evidence. |
| `totalMilliseconds`, `execution` | Runtime and implementation identifier; the current Java result uses `JAVA_SERIAL_EXACT_SIMPLEX`. |

`P[i,j]` is correspondence mass, not a consumed quantity or probability that a material caused an anomaly. Its row sums equal `p[i]`, column sums equal `q[j]`, and total mass equals one. A row can distribute its mass over several right nodes. For a row-level share, `P[i,j]/p[i]` gives the portion of that left node's weight assigned to right node `j`; it still is not an anomaly probability or a physical allocation. All tree nodes participate, including roots and intermediate occurrences. Use the source mappings to recover which materials and production occurrences correspond.

Lower FGW cost means a closer match under the configured content/topology objective. It is not a calibrated percentage similarity, a currency amount or proof of normality. The nonconvex calculation and its stopping status do not guarantee a global optimum. Prices/quantities are retained as evidence and do not enter the present objective.

Only `FW_GAP_REACHED` yields `converged=true`. `COST_STALLED`, `MAX_ITERATIONS` and `NUMERICAL_FAILURE` do not. With `captureTrace=true`, the result additionally contains material and structural distance matrices and per-iteration matrices, objective, marginals, line search and linear-OT diagnostics. Without tracing these optional arrays/lists remain empty; do not promise detailed OT certificates as default output.

The HTTP `ProductionComparisonResponse` wraps this in `result`, together with `statuses`, `missingVectors`, `embeddingVersions`, inspected `trees` and nullable `converged`. Ineligible or incomplete inputs return evidence/statuses with `result=null`; an executed solver can still return a failure or nonconverged result.

## CPU and GPU selection

The caller supplies whether its selected GPU is NVIDIA. `algorithm` accepts this decision directly; it does not discover the vendor, reinterpret it as a hint or choose another GPU family. The proposed contract uses `useGpu` to distinguish CPU operation from non-NVIDIA GPU operation, and `isNvidia` to select the accelerated engine family. Require `isNvidia` when `useGpu=true`; do not default an absent value to false. Device IDs remain configurable and refer to devices within the selected runtime.

| Caller input | Selected computational engine family |
| --- | --- |
| `useGpu=false` | Java CPU frontier and FGW; `isNvidia` is ignored. |
| `useGpu=true`, `isNvidia=true` | CUDA frontier and FGW. |
| `useGpu=true`, `isNvidia=false` | OpenCL frontier and FGW. |

The surrounding application/caller owns any vendor determination. The engine adapter still checks whether its requested library/runtime/device can initialise and execute, but those checks do not change the supplied vendor decision. An unavailable CUDA path must not silently switch to OpenCL, or vice versa. Return an explicit unavailable/initialisation status; use Java only when the caller explicitly permits CPU fallback. Report each fallback and its reason.

CSR and instance construction remain Java in the first integration. `allowCpuFallback=false` does not prohibit these defined Java stages; it prevents substituting CPU for the requested accelerated frontier/FGW stages. A CUDA library for CSR is not a reason to move repository access or instance evidence validation onto the GPU. Frontier and FGW adapters may differ in availability, so report actual execution per stage while preserving the caller-selected engine family.

Each execution report records stage, supplied `useGpu`/`isNvidia`, requested engine family, actual backend/device, fallback reason and numerical mode. Also identify any internal CPU OT use: a CUDA FGW solve with CPU OT is not a complete CPU FGW fallback. Start with CPU adapters only; GPU requests must then report missing binding or explicitly allowed CPU fallback. Do not label native acceleration integrated until bridges and device checks run successfully.

GPU selection changes execution, not algorithm definitions. Preserve frontier threshold semantics and exact signed-long micro-units, and FGW FP64 objective/tolerances. Validate nonnegative finite thresholds representable at six decimal places, signed-long bounds, unique inventory IDs, CSR shape and DAG topology before pruning. Report missing/noncomparable nodes and actual-only/comparable-only identities explicitly. Current discovery operates on the actual graph and cannot be described as symmetric added/removed-material analysis without a specified extension.

## LOF and interpretation

LOF is not part of the proposed mandatory flow. Repository search found only its definition within Java main sources, with Java/native tests still present. Retain it as an optional standalone scoring module unless a separate removal task is authorised. Future production-instance statistical scoring would require defined four features, scaling, population, `k` and interpretation; the current engine does not specify these business rules.

Threshold pruning identifies cost-difference paths. FGW compares material content and topology between actual production trees. It does not replace pruning, and quantity/price changes alone do not alter its current objective. Return recorded/per-output quantity and cost evidence separately where inputs permit. Do not add monetary penalties or new anomaly thresholds to standard FGW, and do not treat its score as causal proof.

## Implementation order and required validation

1. Extract domain contracts/ports and inject Java frontier/FGW engines. Implement the independent CPU workflow and new endpoint without changing existing mathematical behaviour or public endpoints.
2. Test end-to-end graph-to-path-to-instance-to-pair mappings with isolated warehouse fixtures, including no-path short-circuiting, candidate discovery in both periods, full-instance loading across dates, same-material distinct occurrences, all-pairs enumeration and missing evidence/vectors. Check zero/one-ID groups, exactly three pairs for three IDs, exactly 4,950 planned pairs for 100 IDs of one product, deduplication across periods/paths, no self/reverse duplicates, separation of products/namespaces and retained rejection statuses. The 100-ID count check tests enumeration and does not require 4,950 numerical solves in that fixture. Apply all production-tree grouping/evidence checks required by its specification when that behaviour is changed.
3. Test direct Java calls and HTTP/stream adapters against the same workflow results. Test invalid thresholds/topology, cancellation, numerical/eligibility failures and truthful per-stage backend reporting. Validate any frontend changes using the prescribed build plus required behavioural checks.
4. Add native frontier and FGW bridges as separate stages. Run the applicable native device tests, CPU parity fixtures/tolerances and FGW intermediate/independent CPU/POT validation before describing acceleration as available. Establish bridge packaging, supported runtime/device capabilities and lifecycle tests; report unavailable prerequisites explicitly.
5. Only after correctness, add bounded all-pairs scheduling and resource reuse, and measure performance. A session may reuse one left tree while processing its remaining right partners, but this is an internal optimisation: every planned unique pair still executes, and no fixed-baseline selection replaces all-pairs semantics. Keep the independent workflow contract stable.

Relevant specifications: [production workflow](<Restore Product Production Trees and Compare with FGW.md>), [production-ID tree rules](<Build Product Production Trees by Production ID>), [five-step FGW](<FGW Implementation - Implement Standard FGW in Five Steps.md>), [FGW numerical fixture](<FGW Implementation - Test Case.md>) and [existing Java API](<Production Tree Java API.md>). Some historical links inside those documents point to filenames absent from this checkout; the links listed here resolve to the present files. This design does not alter those specifications or their mathematical fixtures.
