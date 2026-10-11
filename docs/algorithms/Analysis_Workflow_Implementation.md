# Analysis workflow implementation

Parent: [Main analysis design](Main_Analysis_Design.md). Scope: reusable Java orchestration, candidate/pair selection and API/progress adaptation. This is an implementation specification, not evidence that proposed contracts are implemented. Each computational step owns its implementation details. The [workflow acceptance tests](Analysis_Workflow_Test_Case.md) own the complete-instance checks and final assertions.

## Structure and dependencies

Keep existing package names and computational modules. Add small boundary modules instead of moving all current code:

| Package | Responsibilities and proposed contracts |
| --- | --- |
| `algorithm/workflow` | `AnalysisWorkflow`, `AnalysisCommand`, `AnalysisOutcome`, `AnalysisProgressListener`, `ProductionPairSelector`: reusable orchestration, domain inputs/results, progress and all-pairs selection. |
| `algorithm/port` | `PeriodGraphProvider`, `ProductionDataProvider`, `EmbeddingContextProvider`, `EmbeddingProvider`: graph/data loading, verified context and vector resolution/generation. |
| `algorithm/embedding` | `MaterialContext`, `EmbeddingPreparation`, `EmbeddingOutcome`: canonical inputs, alignment, provenance and missing evidence. |
| `algorithm/execution` | `ExecutionPolicy`, `BackendRegistry`, `BackendResolver`, `ExecutionReport`: caller-selected engines, registered adapters and actual execution evidence. |
| Existing algorithm modules | Keep `TreeBuilder`, `BOMFrontier`, `ProductionTree`, `FGW`, `LOF` and `model`. Add `FrontierEngine` and `FgwComparator` boundaries; preserve shared reconciliation and solver behaviour. |
| `analysis/controller`, `dto`, `service` | HTTP/NDJSON adaptation, compatible public DTOs, Spring wiring and read-only warehouse providers. |
| `infrastructure/acceleration`, `embedding` | Native wrappers and model/cache adapters; resource lifetime stays behind domain ports. |

The workflow depends on domain contracts and ports. Spring services implement repository-facing ports; native wrappers implement engine boundaries. The workflow must not import controllers, HTTP DTOs, repositories or JNI details. Existing `AttributionWorkflow` accesses repositories and can be wrapped by a `PeriodGraphProvider` initially; no large TreeBuilder rewrite is needed.

When extracting the boundary, replace `ProductionTreeFgwAdapter`'s imports of `analysis.dto.*` with domain inputs/results, with the old service translating its existing DTOs. Extract the current selection rules from `ProductionTreeService` behind a provider that retains selection references. Both old endpoints and the new workflow can reuse it without `algorithm -> analysis -> algorithm` coupling.

Keep database orchestration and pair scheduling in Java. Native engines receive arrays, mappings and numerical configuration; they do not query the warehouse or decide business eligibility. For Java 21, a JNI boundary is a concrete implementation option; native packaging and ABI/error handling still require implementation and tests. No new dependency is required for this design task.

`EmbeddingPreparation` consumes final trees and immutable context evidence through domain contracts. `EmbeddingContextProvider` obtains warehouse/caller evidence; `EmbeddingProvider` resolves or generates vectors. Model clients, tokenisation, batching and cache storage belong behind these ports. Tree construction and FGW numerical engines do not perform model inference or call an external service.

## Callable contracts

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
  "embedding": {
    "mode": "GENERATE_MISSING",
    "profile": "MATERIAL_CONTEXT_V1",
    "modelId": null,
    "modelRevision": null,
    "templateVersion": "1",
    "dimension": 384,
    "normalization": "MODEL_DEFAULT",
    "contextEvidence": {},
    "execution": {
      "backend": "CPU",
      "deviceId": null,
      "allowCpuFallback": false
    }
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

This example illustrates the proposed field layout, not an executable model configuration. Resolve `modelId` and `modelRevision` from verified provider configuration or explicit caller values before generation; unresolved configuration produces `EMBEDDING_INPUT_REQUIRED`. Empty tree/context evidence remains missing input. Empty `comparison.embeddings` can be resolved by the configured generation stage; it must not cause zero-vector substitution. `SUPPLIED_ONLY` performs no inference. Tree evidence and the existing occurrence-keyed vector shape retain the contracts documented in [Production Tree Java API](<Production Tree Java API.md>); new contextual provenance is described below. FGW configuration retains existing defaults and definitions. Thresholds should be decimal strings in the new API to preserve exact client values; old numeric request fields remain accepted by compatibility endpoints. Existing tree comparison endpoints remain caller-supplied-vector endpoints until an implementation task explicitly integrates generation.

If product filtering is added, document and implement it explicitly for both graphs and candidate selection; do not expose an ignored `targets` field. Do not add `company` as a supposed organisation filter: the current warehouse has no organisation column. The evidence namespace labels a scoped dataset and is not row-level isolation.

## Candidate and pair selection

1. Preserve abnormal path references, graph role, selected boundary node and original graph positions. Exclude synthetic `__COGS__` from production identity selection while retaining it in discovery evidence.
2. Follow actual recorded relationships to receiving products. Deduplicate candidates by namespace and production ID, retaining all branch references. A candidate is not a proven abnormal instance.
3. Select candidate production IDs in the actual period. For the same receiving products, discover candidates independently in the comparable period; they do not need their own abnormal paths. Combine both sets and deduplicate scoped production IDs, retaining period membership and all selection evidence. These are the objects for all-pairs comparison, not a fixed-baseline or exclusively cross-period comparison.
4. Load all available records of every selected production ID, including outside selection dates. Build direct/full-lineage trees according to supplied evidence. Never infer upstream production links, FIFO allocation or source shares.
5. Group candidates by namespace and receiving product. Within each group, deterministically order production IDs and enumerate every pair `(i,j)` with `i < j`. Never compare an ID with itself or schedule both `(i,j)` and `(j,i)`. A group of `n` IDs has `n * (n - 1) / 2` planned comparisons: 100 eligible IDs of one product produce 4,950 FGW comparisons. Include actual-to-actual, comparable-to-comparable and cross-period pairs. Do not require caller-supplied pairs or choose a fixed baseline. A group with fewer than two IDs returns `INSUFFICIENT_PRODUCTION_INSTANCES`.
6. Check each planned pair for same-product, different-production-ID, namespace, topology-scope and completeness eligibility before numerical execution. Return per-pair rejection evidence rather than silently dropping invalid pairs. Planned, executed, rejected and numerically failed counts must be reported separately; 4,950 planned pairs are not 4,950 successful solves when inputs are incomplete.
7. Prepare each occurrence's finite 384-dimensional contextual vector using the stage below, including roots, before executing eligible pairs. All pairs reuse this run's prepared vectors. Missing context or vectors remains explicit per-occurrence/per-pair evidence. The twelve-field profile supersedes the earlier material-only input interpretation for the proposed workflow; existing numerical fixtures and legacy supplied-vector endpoints remain unchanged.

If no paths are found, short-circuit instance selection/comparison and report `NO_ABNORMAL_BRANCHES`. In particular, do not call the current `ProductionTreeService.inspect` with only dates and an empty `abnormalPaths` list: that combination selects all instances in the period. Missing comparison data should retain discovery results with `COMPARISON_INPUT_REQUIRED`. Missing costs are explicit incomplete discovery evidence, not proof of normality.

## Output and progress

The result should include `analysisId`, final status, both graph snapshots, abnormal paths, production selection/source references, inspected trees, embedding preparation manifests/statuses/counts, deterministically ordered planned/executed/rejected pairs and their counts, per-pair FGW outputs and stage execution reports. FGW output retains correspondence, material/structure/FGW costs, convergence, numerical diagnostics and occurrence/source mappings. Decimal financial values remain strings in new result fields.

Proposed streaming events are `graph`, `branches`, `production-trees`, `embeddings`, `comparison`, `completed` and `error`, all sharing the run ID and increasing sequence number. The `embeddings` event reports preparation counts, provenance, execution and missing inputs; raw vectors need not be streamed. Existing `/reconcile-periods/stream` retains its `graph`/`diff` protocol. Controllers translate progress events without rebuilding graphs, regenerating embeddings or rerunning the solver. Source evidence must come from the same loaded run snapshots; clients join comparison occurrences to tree/issue evidence using IDs, not inventory IDs alone.

Use an isolated, read-only consistent database snapshot for source loading, including material descriptions and available supplier/cost evidence, then retain immutable loaded inputs for computation. End the source-loading transaction before model/service inference and numerical comparison. Do not keep a database transaction open while waiting for new caller evidence. A run with missing evidence returns its partial outcome; a subsequent submission reloads current data and identifies its new run. Persistent/resumable runs would need a separate snapshot/version contract.

## Execution sequence and resource lifetime

1. Load both period snapshots through `PeriodGraphProvider` and construct CSR as specified in [step 1](CSR_Implementation.md).
2. Prepare/prune/reconstruct abnormal branches as specified in [step 2](Abnormal_Branches_Implementation.md). On no paths, return the defined short-circuit outcome before loading production instances or invoking a model.
3. Select scoped production IDs in both periods using the candidate rules above. Load complete instances and execute [two-pass tree restoration](FGW_Build_Production_Trees.md). Retain the preceding graph, coverage and selection references.
4. Prepare [occurrence-context embeddings](Embedding_Implementation.md) after final occurrence indexing. Retain immutable tree/vector snapshots and enumerate all unique pairs according to the rules above.
5. Apply per-pair eligibility, execute [standard FGW](FGW_Implementation_Five_Steps.md), and join results to source mappings. Return rejected or failed pairs alongside accepted results and distinct planned/executed/rejected/failed counts.
6. Close numerical/inference resources and return the same outcome through direct Java and compatible HTTP/progress adapters. Cancellation must stop further submissions and drain/release owned work safely.

Use bounded pair scheduling and owned session resources. Reusing a left-tree baseline is an internal resource optimisation; it must not replace all-pairs selection. Keep numerical correctness ahead of performance optimisation and follow the execution policy in the parent design.
