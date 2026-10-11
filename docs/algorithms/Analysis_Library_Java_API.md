# Independent Java analysis library

Implementation of the initial CPU integration in [Main](Main_Analysis_Design.md), with the original frontier and standard balanced FGW definitions. The [task record](../tasks/2026-10-12-005606-complete-analysis-library.md) identifies executed checks and limitations. Native Java bridges remain unavailable; native source files do not establish accelerated execution.

## Direct invocation and source boundaries

`algorithm.workflow.AnalysisWorkflow.run(AnalysisCommand)` returns one `AnalysisOutcome`. The overload accepting `AnalysisProgressListener` emits `graph`, `branches`, `production-trees`, `embeddings`, each `comparison`, and `completed` with one run ID and increasing sequence numbers. A cancellation supplier is also available; thread interruption stops Java FW/OT iterations and owned inference waits. There is no new HTTP streaming endpoint in this initial integration.

Supply these ports:

- `PeriodGraphProvider`: constructs both period CSR graphs and retains original records, coverage and source-to-position mappings. `RecordPeriodGraphProvider` reuses the current attribution handler chain with `PeriodRecords`; it accepts sales and issue rows, never prepared CSR.
- `ProductionDataProvider`: selects receiving roots and loads whole instances and terminal candidates. `RecordProductionDataProvider` follows actual branch/issue relationships to sold receiving products, then independently selects the same products' instances in both periods. Supplying instances are lineage evidence, rather than additional independently selected comparison roots. This makes the shared fixture select P0/P1, with S1/S2 loaded through explicit source links. Original out-of-scope candidates remain visible to the builder.
- `EmbeddingContextProvider`: supplies occurrence-specific verified descriptions, supplier/source-kind, decimal unit cost, unit/currency/scope/basis and source references. Neighbours are extracted from the final occurrence tree. Incomplete or failed context queries retain per-occurrence evidence.
- `EmbeddingProvider`: exposes pinned model/runtime provenance, exact token counts and identified batch responses. `PythonEmbeddingProvider` owns a warm local process and closes it on completion, failure, timeout or cancellation. The inference policy is separate from frontier/FGW selection.

`ProductionPairSelector` deduplicates scoped production IDs, groups by namespace/product and enumerates all `i < j` pairs in deterministic order. It includes within-period and cross-period pairs. Solves are serial, with one live solve; `JavaFgwComparator` reuses the current left-tree preparation across its remaining partners. Vectors are prepared once per run and reused for every pair. No solved correspondence is cached.

The source loader freezes immutable sales/issues in a read-only repeatable-read transaction, then ends that transaction before inference or FGW. It currently loads the connected warehouse's records into memory. Namespace is a caller-declared dataset label, not a row-level organisation filter. Period dates limit discovery/selection; all available selected-instance rows remain available to restoration. Tree graph coverage is checked separately.

## New synchronous endpoint

`POST /api/v1/analysis/run` accepts the domain command. Existing trace/reconciliation/tree endpoints retain their contracts; the existing supplied-vector tree endpoint is translated through `analysis.service.ProductionComparisonAdapter` to the domain-only FGW gate.

The initial request concretises the proposed layout as follows. It supports only all-pairs comparison, the fixed `MATERIAL_CONTEXT_V1/1` template and 384-dimensional vectors; there are no ignored product/company filters or dimension/profile switches.

```json
{
  "actualStartDate": "2026-09-01",
  "actualEndDate": "2026-09-30",
  "comparableStartDate": "2026-08-01",
  "comparableEndDate": "2026-08-31",
  "leafThreshold": "10.000000",
  "stopThreshold": "25.000000",
  "execution": {
    "useGpu": false,
    "isNvidia": null,
    "deviceId": null,
    "allowCpuFallback": false
  },
  "embedding": {
    "mode": "GENERATE_MISSING",
    "modelId": null,
    "modelRevision": null,
    "normalization": "L2",
    "backend": "CPU",
    "deviceId": null,
    "allowCpuFallback": false
  },
  "comparison": {
    "evidence": null,
    "completeGraphCoverage": false,
    "contextEvidence": {},
    "embeddings": {},
    "config": {"alpha": 0.8}
  }
}
```

This is a partial request example, not complete tree/context evidence. Supply `comparison.evidence` using the existing [tree evidence contract](<Production Tree Java API.md>); its `scope` selects direct consumption or full lineage. `completeGraphCoverage` is a caller declaration and defaults to false. `contextEvidence` is keyed by final occurrence ID and contains the fields of `MaterialContext.Evidence`, including description/source references and `conflicting`. `comparison.embeddings` contains `inputHash`, `signature`, and `values` for each supplied occurrence. The canonical hash and full signature must match the verified twelve-field input and resolved model configuration. Legacy opaque vector versions do not prove this alignment.

Absent embedding mode/backend/normalisation default to generation/CPU/L2. `MODEL_DEFAULT` resolves to the provider's reported normalisation, L2 for this adapter. The local adapter supports pinned 384-dimensional mean-pooled sentence-transformer models, L2 output and FP32 inference. `SUPPLIED_ONLY` validates the declared MEAN/L2/FP32 signature without loading a model, tokenising or invoking inference. Model ID and immutable revision must still be configured. Remote inference is an optional unimplemented adapter; CUDA inference requires a compatible PyTorch installation/device and has not been validated here.

All `BigDecimal` fields in the new HTTP response are plain decimal strings, including source rows and graph costs. FGW `double` values remain numerical objective values. Direct Java calls retain `BigDecimal` source values. Legacy endpoint serialisation is unchanged.

## Context, cache and partial results

Canonical inputs collapse whitespace, escape list/value separators, use plain stripped decimal text and sort supplier and aligned neighbour tuples. Distinct neighbour occurrences and multiplicity survive; production/occurrence/issue IDs remain provenance and do not enter semantic text. Missing fields, incompatible unit/currency/scope, conflicting context, unverifiable supplied provenance, overlength input and invalid/nonfinite/zero-norm vectors are explicit statuses. No vector padding, truncation or zero placeholders occur.

Preparation resolves supplied inputs and a bounded 1,024-entry namespace/context/model/template/normalisation/precision cache before batches of at most 16 unresolved unique descriptions. Identified responses may arrive out of order. `supplied`, `cached`, `generated` and `unresolved` count occurrences; `uniqueInputs` counts canonical descriptions; `submittedInputs` counts attempted unique inference submissions; `inferredInputs` counts successfully validated unique generated outputs. Each occurrence keeps its own manifest even when vectors are shared.

Outcomes retain both graph snapshots, original source mappings, selected/excluded tree evidence, embedding manifests, deterministic pair results and separate planned/executed/rejected/failed counts. Missing comparison evidence returns partial discovery with `COMPARISON_INPUT_REQUIRED`. No paths returns `NO_ABNORMAL_BRANCHES` and short-circuits instance/model loading; missing/noncomparable discovery evidence is still reported and does not prove normality. Fewer than two eligible identities produces `INSUFFICIENT_PRODUCTION_INSTANCES`. Numerical failures retain their solver diagnostics and do not count as successful solves. Completion does not imply solver convergence or calibrated semantic quality; inspect the individual FW statuses.

`useGpu=true` requires `isNvidia`. CUDA and OpenCL requests currently report missing Java bindings, or explicitly permitted Java fallback, per computational stage. The stage reports distinguish selected availability from actual execution. Inference reports its own requested/actual backend, runtime, device, precision and fallback. FP32 inference does not relax FP64 FGW tolerances.

## Reproducible CPU model setup and acceptance

Use Python 3.12 and an isolated environment; Java remains Java 21. From the repository root:

```sh
python3 -m venv .venv-embedding
.venv-embedding/bin/pip install -r backend/src/main/resources/embedding/requirements.txt
export MARGIN_EMBEDDING_PYTHON="$PWD/.venv-embedding/bin/python"
export MARGIN_EMBEDDING_MODEL=sentence-transformers/all-MiniLM-L6-v2
export MARGIN_EMBEDDING_REVISION=1110a243fdf4706b3f48f1d95db1a4f5529b4d41
"$MARGIN_EMBEDDING_PYTHON" -c 'import os; from sentence_transformers import SentenceTransformer; SentenceTransformer(os.environ["MARGIN_EMBEDDING_MODEL"], revision=os.environ["MARGIN_EMBEDDING_REVISION"], device="cpu")'
./backend/mvnw --batch-mode --no-transfer-progress -f backend/pom.xml test
```

Downloads are explicit setup; the runtime uses `local_files_only=True`. Set `HF_HOME` to a configurable shared cache if required. Spring uses `margin.embedding.python`, `margin.embedding.model`, `margin.embedding.revision` and `margin.embedding.timeout-seconds` (default 60). The corresponding environment variables above configure the server and the real-model tests. No model is silently downloaded or selected by the server.

The model's full transformer position limit, 512 for this pinned MiniLM revision, is explicit runtime provenance. Inputs exceeding that limit are rejected before inference; all twelve fields are retained. The shorter SentenceTransformers default truncation length is not used. Actual model/runtime versions are in the captured report, rather than a promise of identical vectors on other machines.

With the three model environment variables unset, the two real-model tests are reported as unverified/skipped, while ordinary deterministic-provider checks still execute. The process timeout/cancellation test also requires the configured Python executable. With them set, the stepwise instance and a separate complete invocation use original records, the real encoder and real Java FGW. Repeated CPU inference is checked at a frozen component tolerance of `1e-6`; numerical FGW checks retain `1e-8`. Test output goes to `backend/target/analysis-validation/`.

Use a separate environment installed from the existing `benchmark/fgw/requirements.txt` for numerical validation:

```sh
python3 -m venv .venv-fgw
.venv-fgw/bin/pip install -r benchmark/fgw/requirements.txt
.venv-fgw/bin/python benchmark/fgw/validate_workflow.py \
  --input backend/target/analysis-validation/numerical-inputs.json \
  --output backend/target/analysis-validation/independent-validation.json
```

This checks the captured vectors/CSR, every FW state, original tensor objectives/gradients, exact HiGHS/POT OT objectives, marginals, line search and fresh terminal gap at `1e-8`. The existing fixed regression and exact-OT harness remain separately required. Reports preserve human-labelled correspondence masses and scales; no fixed FGW score or semantic acceptance threshold is invented. Native bridge/device validation and calibrated semantic acceptance remain unverified.
