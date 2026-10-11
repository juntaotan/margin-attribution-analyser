# Independent analysis workflow design

This document proposes how to connect existing algorithms. It does not implement the workflow or establish runtime/device correctness. Repository inspection was performed on 11 October 2026. Material comparison is interpreted as the existing production-instance FGW comparison; decimal consumption/cost evidence remains a separate result.

## Document hierarchy and reading scope

This is the central design for the computational sequence, common invariants and execution policy. Read the relevant step implementation rather than every document. [FGW algorithm design](FGW_Algorithm_Design.md) remains the FGW centre for restoration/comparison boundaries and result interpretation.

| Step | Implementation | Test document |
| --- | --- | --- |
| 1. Period CSR | [CSR](CSR_Implementation.md) | [CSR tests](CSR_Test_Case.md) — user-confirmed scope. |
| 2. Abnormal branches | [Branch discovery](Abnormal_Branches_Implementation.md) | [Branch tests](Abnormal_Branches_Test_Case.md) — user-confirmed scope. |
| 3. Production trees | [Tree restoration](FGW_Build_Production_Trees.md) | [Tree tests](FGW_Build_Production_Trees_Test_Case.md) — user-confirmed scope. |
| 4. Context and embeddings | [Embedding preparation](Embedding_Implementation.md) | [Embedding tests](Embedding_Test_Case.md) — user-confirmed scope. |
| 5. FGW comparison | [Five-step solver](FGW_Implementation_Five_Steps.md) | [Existing numerical regression](FGW_Test_Case.md) — preserved without rewriting. |
| 6. Complete orchestration | [Workflow](Analysis_Workflow_Implementation.md) | [Workflow tests](Analysis_Workflow_Test_Case.md) — user-confirmed scope. |

[Shared fixture](Analysis_Fixture.md) contains reusable source data. [Java API reference](<Production Tree Java API.md>) is consulted when existing callers/contracts are affected. Historical reports are evidence for specific questions, not default reading.

All six step test documents now contain confirmed specifications or the existing FGW regression. Acceptance requirements are linked from their implementation documents; no empty placeholder waives validation and no documentation edit establishes a runtime pass.

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
| Occurrence-context embedding preparation | Proposed stage; no Java generation service identified | Current comparison requests supply vectors externally. The proposed stage encodes twelve fields after final instance-tree construction. |
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
     -> collect twelve-field occurrence contexts -> resolve/cache/batch-generate 384-dimensional embeddings
     -> map contextual vectors to final occurrence IDs and CSR indices
     -> enumerate all unique same-product production-ID pairs
     -> validate completeness and occurrence embeddings for each pair
     -> FGW -> join correspondence to source evidence
     -> return paths, selected instances, comparisons and stage statuses
```

The product-facing description can remain “CSR -> abnormal branches -> material comparison”. Instance selection/tree construction is the necessary adapter between the latter two stages.

Do not feed a period `CsrGraph` directly to FGW as an actual production instance. It has already merged inventory IDs across production records, and the synthetic COGS node is not a receiving production. Retain period graphs for discovery; rebuild occurrence trees from original records for accepted comparisons. Preserve branch/node/edge mappings throughout.

### Workflow and embedding implementation

[Workflow implementation](Analysis_Workflow_Implementation.md) owns package/port boundaries, callable and external API contracts, candidate selection, all-pairs enumeration, consistent source snapshots, progress/results and resource lifetime. [Embedding implementation](Embedding_Implementation.md) owns the twelve-field context, canonicalisation, provenance, cache/batch preparation and independent inference execution. Existing public endpoints remain compatible.

### FGW result interpretation

See [FGW comparison results](FGW_Algorithm_Design.md#comparison-result-interpretation) for correspondence, objective terms, convergence and source mapping. The workflow returns these results alongside exact decimal financial evidence; it does not turn them into monetary variance or causal diagnoses.

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

### Independent embedding execution

See [embedding execution](Embedding_Implementation.md#inference-execution-and-resource-lifetime). Its CPU/GPU/REMOTE policy is independent of frontier/FGW engine selection.

## LOF and interpretation

LOF is not part of the proposed mandatory flow. Repository search found only its definition within Java main sources, with Java/native tests still present. Retain it as an optional standalone scoring module unless a separate removal task is authorised. Future production-instance statistical scoring would require defined four features, scaling, population, `k` and interpretation; the current engine does not specify these business rules.

Threshold pruning identifies cost-difference paths. FGW compares node attributes and topology between actual production trees. It does not replace pruning. The proposed context vector includes unit cost, but textual encoding supplies no exact financial sensitivity or anomaly threshold; fixed-vector metadata-only quantity/price changes still do not alter the solver objective. Return recorded/per-output quantity and cost evidence separately where inputs permit. Do not add monetary penalties or new anomaly thresholds to standard FGW, and do not treat its score as causal proof.

## Acceptance entry point

Run the [workflow acceptance specification](Analysis_Workflow_Test_Case.md) against the [shared fixture](Analysis_Fixture.md). It owns the six computational-step checks, final financial/numerical/semantic assertions and supporting integration/execution requirements. Each step must run a concrete instance, followed by one complete invocation from original records through real embeddings and FGW. Existing individual-step tests remain mandatory for the affected scope. Numerical correctness, financial correctness and calibrated semantic quality are reported separately; unavailable prerequisites remain unverified.
