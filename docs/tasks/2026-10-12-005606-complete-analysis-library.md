Start time: 2026-10-12 00:56:06 Pacific/Auckland (UTC+13:00)
End time: 2026-10-12 02:10:40 Pacific/Auckland (UTC+13:00)
Summary: Implemented and validated the independent CPU analysis library and HTTP adapter, including the documented original-record instance, real contextual embeddings and standard balanced FGW. Native acceleration and semantic-quality acceptance remain unverified.
Details:
- Changes: The initial working tree was clean. Reused the existing period handler chain, Java frontier, production-ID two-pass builder and FP64 FGW solver. Added domain ports, immutable original-record snapshots, source-aware candidate selection, deterministic all-pairs scheduling, scoped session reuse, contextual embedding preparation and an owned local Python inference process. Added a read-only warehouse loader and `/api/v1/analysis/run`; retained existing endpoints through a compatibility adapter. Added acceptance tests, an independent numerical validator and API/setup documentation. No commits were created.
- Validation: The complete Java 21 Maven test suite passed 186 tests, with zero failures, errors or skips and real-model configuration enabled. After the final null-vector compatibility correction, all 12 affected production-tree tests passed. Real-vector independent NumPy/SciPy/POT validation passed 80 checks at `1e-8`; maximum absolute error was `2.9245317278991934e-10`. Each of four Java simplex modes passed 397 independent fixed-fixture/exact-OT checks; maximum error was `4.4444337099491804e-11`. Content/reference, archived JSON and whitespace checks passed.
- Limitations: Native frontier/FGW bridges, packaging and device validation are not implemented or verified. Docker, CMake and CUDA prerequisites were unavailable; the available OpenCL device reported zero FP64 capability. Model-specific semantic thresholds are not calibrated. Optional GPU inference is not device-tested and no remote inference provider is implemented. CPU timings are small-fixture measurements, not production-readiness or accelerator-performance evidence.

## Implementation and affected files

- `backend/src/main/java/dev/margintrace/margin_attribution_backend/algorithm/port/`: source snapshots, period/production/context/model ports and provenance. `algorithm/TreeBuilder/ReadDataHandler.java` and `EdgeBuildHandler.java` gained source functions alongside existing repository constructors; `RecordPeriodGraphProvider.java` executes that same chain over original records. Missing amounts remain missing rather than fabricated zeroes.
- `algorithm/ProductionTree/RecordProductionDataProvider.java`: follows branch mappings to sold receiving products independently in both periods, retains all source references and excluded candidates, loads complete production instances across date boundaries, and supplies the existing builder's two lookup passes. Source intermediate batches remain lineage evidence rather than extra root candidates.
- `algorithm/BOMFrontier/`: injectable `FrontierEngine`, CSR/DAG and signed-long micro-unit threshold validation; propagation mathematics and actual-side discovery semantics are preserved.
- `algorithm/embedding/`: the twelve-field canonical profile, final-tree neighbour alignment, supplied-vector provenance, bounded namespace/model/context cache, identified batches of at most 16, explicit missing/invalid/overlength/partial outcomes and occurrence/unique/submission counters. Cache and supplied reuse retain distinct occurrence manifests.
- `infrastructure/embedding/` and `backend/src/main/resources/embedding/`: a pinned, local-only SentenceTransformers encoder, exact token bounds, mean pooling, FP32 L2 vectors and process ownership. Timeout and cancellation terminate owned work. `SUPPLIED_ONLY` needs no Python/model process.
- `algorithm/execution/`, `algorithm/workflow/`, `algorithm/FGW/JavaFgwComparator.java` and `FgwComparator.java`: CPU engine boundaries, independent inference policy, synchronous bounded pair scheduling, per-run/current-left session ownership, stable run/sequence progress and separate planned/executed/rejected/failed counts. `FgwEngine.java` now honours interruption in iterative numerical work; its definitions and fixture expectations are unchanged.
- `ProductionTreeFgwAdapter.java`: pure domain comparison contract and copied/aligned graph inputs. `analysis/service/ProductionComparisonAdapter.java` translates existing HTTP DTOs, including null vectors as missing inputs. Existing production-tree service and test callers use that compatibility adapter.
- `analysis/service/AnalysisSourceLoader.java`, `AnalysisRunService.java`, `analysis/controller/AnalysisRunController.java` and `analysis/dto/AnalysisRunResponse.java`: read-only repeatable-read source loading, configured model creation, one-run endpoint and exact decimal strings for financial fields. Numerical FGW doubles remain numeric. Context evidence must be supplied explicitly; absent warehouse descriptions/supplier evidence are not invented.
- Tests under `backend/src/test/java/.../algorithm/{TreeBuilder,BOMFrontier,ProductionTree,workflow}/`, `analysis/AnalysisSourceLoaderTest.java` and `infrastructure/embedding/PythonEmbeddingProviderTest.java`; `benchmark/fgw/validate_workflow.py`; [library API/setup](../algorithms/Analysis_Library_Java_API.md) and the existing production-tree API reference.
- `.gitignore`: narrow exceptions for this task's record and captured evidence, following existing task-specific exceptions, so they remain reviewable with the uncommitted implementation.

No frontend, schema, native implementation, live database or object-storage volume was changed. The warehouse integration test uses isolated H2 data with rollback. The source loader currently retains the full warehouse snapshot in memory; large-data loading remains a limitation.

## Required validation checklist

| Requirement | Result and evidence |
| --- | --- |
| C01–C10 period CSR | PASS. Original sales/issues enter the existing construction chain; exact arrays, decimal/large values, shared/split relationships, inclusive boundaries, empty periods and missing-input reporting are asserted in `PeriodCsrAcceptanceTest`. |
| B01–B10 abnormal branches | PASS. Existing frontier assertions plus `AbnormalBranchAcceptanceTest` cover propagation/reconstruction, changed relationships, actual-only records, moved leaves, missing costs, reindexing, threshold limits and invalid topology. |
| T01–T14 production-tree boundaries | PASS across existing builder/two-pass tests and the additional exact T04/T05/T07 fixtures: totals 45, 100 and 115, shared terminals as separate occurrences and overdraw rejection. Existing grouping, evidence, incomplete lineage, allocation and financial assertions were retained. |
| E01–E10 contextual embeddings | PASS for deterministic acceptance checks, including all fields, directions/multiplicity, canonical ordering, missing/conflicting evidence, provenance, namespace isolation, cache invalidation, reordered replies, dimensions/nonfinite/zero norms, model signatures, partial outputs, timeout/cancellation and truthful fallback. These checks supplement the real-model instance. |
| Steps 1–6 full-flow instance | PASS on CPU. Each computational output is asserted before use; a separate workflow call again starts from original records without supplied final CSR, trees or vectors. See the captured evidence below. |
| Selection and all-pairs | PASS. Zero/one-ID cases, three distinct IDs with three executed pairs, 100-ID enumeration with exactly 4,950 planned pairs, no self/reverse duplicates, product/namespace isolation and retained rejected/numerically failed outcomes. The 100-ID case does not claim 4,950 solves. |
| Direct/HTTP/progress and data access | PASS. The same Java outcome is exposed through the new HTTP adapter; progress shares one ID/increasing sequence. Decimal-string responses, invalid commands, isolated H2 snapshots and across-date instance loading are asserted. No new HTTP streaming endpoint was introduced. |
| Inference lifecycle | PASS on the configured CPU process. Real inference plus an actual owned protocol process exercise timeout/interruption/cleanup; the protocol process is not used as the full-flow model. CPU repeat tolerance was frozen at `1e-6`, observed maximum component error zero. |
| Five-step FGW and fixed numerical fixture | PASS for Java CPU in modes 0–3. Independent tensor objective/gradient, exact HiGHS/POT OT, marginal feasibility, certificates, feasible directional derivative, quadratic line search, applied updates and fresh stopping gaps are checked without changing expected values. |
| Real manufacturing-context perturbations | MEASURED. Code, grade, supplier, unit cost and neighbour direction/identity variants have captured inputs, embedding changes, objective terms and correspondence. They are hypothetical semantic perturbations, not altered financial acceptance fixtures. Semantic acceptance remains UNVERIFIED. |
| Native bridges/device parity/lifecycle | UNVERIFIED and unimplemented. Main explicitly starts with CPU adapters; no CUDA/OpenCL execution is labelled integrated. See prerequisites below. |

## Full-flow actual and expected results

Discovery thresholds were frozen at leaf `10` and stop `25`, using existing frontier rules. The path is `R1 → S1 → A → __COGS__`; S1's difference 20 remains below the stop threshold, and the selected COGS boundary difference is 30. A's legacy discovery cost is missing and reported `NONCOMPARABLE_COST:A`. Actual-only R5 and comparable-only R4 are reported explicitly; the result does not claim symmetric replacement discovery.

P0/P1 each has exactly seven comparison occurrences and six edges, one A root, complete declared lineage/scope/cost evidence and no synthetic COGS occurrence. Expected and actual monetary results agree exactly:

| Financial measure | P0 | P1 |
| --- | ---: | ---: |
| Root material subtotal | 220 | 250 |
| Labour | 12 | 12 |
| Overhead | 8 | 8 |
| Complete production total | 240 | 270 |
| Output quantity | 10 | 10 |
| Complete per-output cost | 24 | 27 |
| Complete-total discrepancy | 0 | 0 |
| Preserved material-only discrepancy | 20 | 20 |

Steel quantity remains 20 kg and amount rises from 100 to 120; coating quantity remains 5 kg with R4 amount 30 replaced by R5 amount 40. Total cost difference is exactly 30 NZD. These observations do not establish approved substitution or causal attribution.

The real encoder produced 14 occurrence mappings from 12 unique canonical descriptions; all 12 were submitted/inferred once, with no unresolved inputs. Model: `sentence-transformers/all-MiniLM-L6-v2`, revision `1110a243fdf4706b3f48f1d95db1a4f5529b4d41`, `MATERIAL_CONTEXT_V1/1`, MEAN/L2/FP32, CPU, Torch 2.14.1. Maximum tokens are 512; no truncation/padding substitute is used.

Exactly one pair was planned/executed, none rejected/failed. FGW used Java FP64 exact simplex, alpha 0.8, uniform outer-product start, absolute/relative cost and gap tolerances `1e-6`/`1e-5`, max iterations 200, cost floor `1e-12`, feasibility `1e-9`, numerical `1e-10`, OT `1e-12`. Final attribute cost was `0.023066566923985787`, structure cost zero and FGW cost `0.004613313384797157`; status `FW_GAP_REACHED`, one applied update, fresh gap zero. Independent CPU and POT agree within `1e-8`.

Each human label A↔A, S1↔S1, S2↔S2, R1↔R1, R2↔R2, R3↔R3 and R4↔R5 receives mass 1/7 and row-normalised share 1. Attribute distances and structure contributions are recorded, without an invented semantic PASS threshold.

## Reproducibility and evidence

The documented Docker default could not run because the daemon socket was unavailable. Host Java 21 was intentionally available and used instead:

```sh
cd backend
./mvnw --batch-mode --no-transfer-progress test
./mvnw --batch-mode --no-transfer-progress -Dtest=ProductionTreeBuilderTest test
```

The first command ran with the pinned model environment from the [setup guide](../algorithms/Analysis_Library_Java_API.md); the second followed the null-vector compatibility correction. Full-suite completion was at 01:55:35 Auckland; the affected check completed at 02:04:12. No build-only check is substituted for these tests.

Independent numerical validation uses `benchmark/fgw/requirements.txt`: NumPy 2.5.3, SciPy 1.18.1, POT 0.9.7.post1. Run from the repository root after Maven creates the captured inputs:

```sh
.venv-fgw/bin/python benchmark/fgw/validate_workflow.py \
  --input backend/target/analysis-validation/numerical-inputs.json \
  --output backend/target/analysis-validation/independent-validation.json
```

For each fixed mode 0–3, a Java launcher invoked the existing `dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwFixtures` class from `backend/target/classes:backend/target/test-classes`, passing `FGW_SIMPLEX_MODE`, then the existing `benchmark/fgw/validate.py` consumed its report and launcher. All 397 checks per mode passed, including the fixed 2×3 intermediate expectations and cold/warm exact-OT fixtures. Native coverage in these reports explicitly remains unverified.

Archived evidence is in [this task's directory](2026-10-12-005606-complete-analysis-library/):

- [Complete outcome with original records and manifests](2026-10-12-005606-complete-analysis-library/full-flow.json).
- [Captured real vectors/CSR/configuration](2026-10-12-005606-complete-analysis-library/numerical-inputs.json), [independent actual/expected matrices and errors](2026-10-12-005606-complete-analysis-library/independent-validation.json) and [readable numerical report](2026-10-12-005606-complete-analysis-library/independent-validation.md).
- [Separate stepwise/full-call evidence](2026-10-12-005606-complete-analysis-library/stepwise-smoke.json) and [model perturbations/timings](2026-10-12-005606-complete-analysis-library/model-quality.json).
- [Four-mode summary](2026-10-12-005606-complete-analysis-library/fixed-summary.json). Each mode has `fixed-input-N.json`, `fixed-validation-N.md` and losslessly compressed `fixed-validation-N.json.gz` retaining every actual/expected matrix; decompress with standard gzip tools. The compression only reduces repository artefact size.

CPU timing measurements for this fixture: cold model load 5,654.765 ms; initial preparation 381.668 ms; six warm perturbations 48.529 ms; cache hit 1.253 ms; warm original-record full invocation 112.295 ms. These are single-run observations and have no performance acceptance threshold.

## Missing prerequisites and remaining limitations

- Docker daemon/socket unavailable; the default container tests were not executed. Host Java 21 tests passed. CMake was absent, so the prescribed native configure/build/CTest sequence did not execute.
- The OpenCL capability query succeeded outside the sandbox: one Apple M1 Pro device, no `cl_khr_fp64`, `CL_DEVICE_DOUBLE_FP_CONFIG = 0`. It cannot validate the required FP64 FGW path. This is an observation about this environment, not a portable device default.
- `nvcc` and `nvidia-smi` were absent. No compatible CUDA device/runtime or reproducible CUDA test container was available. Existing CUDA build-path issues were not resolved because no native implementation/build change was made.
- Native frontier/FGW Java bridges, ABI/packaging, device resource lifetime, parity, memory/race checks and accelerated benchmarks remain unimplemented/unverified. GPU requests return missing-binding status or explicit caller-authorised CPU fallback; NVIDIA selection never silently switches engine family.
- Optional CUDA inference and any remote provider remain unverified; no remote provider was added. Numerical CPU acceptance does not establish cross-device model parity.
- Semantic manufacturing quality remains unverified pending calibrated, frozen model-specific thresholds. The full-flow fixture and perturbations measure behaviour, not production readiness.
- Caller context evidence and complete allocation/relationship coverage remain required. Missing inputs produce explicit partial/rejection statuses. Full snapshot loading has not been validated at production warehouse scale.
