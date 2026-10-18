# Anomaly investigation assistant (RAG) design

Status: backend implemented as an optional, disabled-by-default prototype on branch `feature/rag-investigation`. The frontend view is not implemented; it is deferred to the planned frontend rebuild (see [Frontend view](#frontend-view-deferred)). Validation results are recorded in [Validation](#validation).

## Goal

When an analyst investigates an anomaly (material overuse, unit-cost change, material-structure change or substitution), the assistant returns an **investigation report** as JSON. The report:

1. Uses the backend's computed diagnosis to locate the affected material, products and production orders.
2. Retrieves relevant engineering change notices (ECNs), quality records and historical anomaly cases through hybrid retrieval-augmented generation (RAG).
3. Separates three kinds of statement:

| Kind | Source | Written by the model? | Rule enforced in code |
| --- | --- | --- | --- |
| Verified facts | Deterministic diagnosis | No | Copied from the diagnosis evidence |
| Business context | Retrieved document chunks | Yes | Each item cites only known IDs, including at least one retrieved source `Sn` |
| Hypotheses | Model reasoning over facts and sources | Yes | Each item cites only known fact `Fn` or source `Sn` IDs, names a next check and is labelled "Needs verification" |

Generated prose never replaces numerical evidence. Items with invalid or missing citations are removed and counted, not repaired.

Scope is deliberately small: one endpoint returning one JSON report. No Word export, Report Studio integration or report persistence. The existing short root-cause summary (`POST /api/v1/analysis/root-cause-report`) keeps its contract and behaviour.

## Isolation

| Concern | Implementation |
| --- | --- |
| Backend code | `investigation/` (configuration, assistant, service, controller, report contract) and `investigation/knowledge/` (ingestion, repository, retriever, embedding adapter). Existing classes are used only through public methods. |
| Edits to existing code | One seam: `RootCauseReportService.diagnosis(request)` returns the existing classification, evidence, both period nodes and parent products without the model call. `report()` now calls it; its behaviour is unchanged. |
| Feature flag | `margin.investigation.enabled` (environment `INVESTIGATION_ENABLED`, default `false`). Every feature bean carries `@ConditionalOnProperty`; when disabled there are no beans, endpoints or schema. |
| Database | The feature runs its own Flyway migrations from `classpath:db/investigation` with history table `investigation_schema_history`, after the main migrations and only when enabled. The relational tables use plain JDBC rather than JPA entities, so the main `ddl-auto=validate` check is unaffected when the feature is off. |
| Chat model | An `OpenAiChatModel` with JSON-schema output is built per call from `AiRuntimeManager.current().settings()`, so the shared AI runtime classes are unchanged and Settings changes apply immediately. |
| Embedding model | `LocalEmbeddingModel` adapts the existing `EmbeddingProvider`/`PythonEmbeddingProvider` (`margin.embedding.*`), starting the process lazily so the application starts without the runtime installed. |
| Endpoints | `/api/v1/investigation` and `/api/v1/investigation/knowledge/documents`. |
| Shared files changed | `backend/pom.xml` (one dependency), `postgres/Dockerfile` (one package), `application.properties` (two properties), `DEPLOYMENT.md` (a note). |

Removing the feature means deleting `investigation/`, `db/investigation/`, the feature tests and fixtures, the two properties, the dependency and the seam method; the feature tables can be dropped separately.

## Architecture

```
Ingestion  POST /api/v1/investigation/knowledge/documents (file, sourceType, title, effectiveDate)
  RawFileStorage (raw file kept) ─► parse (.docx via POI, .txt/.md, .csv one passage per row)
  ─► DocumentSplitters.recursive(200 tokens, 30 overlap, model tokenizer) ─► embed
  ─► knowledge_document, knowledge_chunk, knowledge_chunk_code (known warehouse codes) ─► knowledge_embedding

Investigation  POST /api/v1/investigation (same body as the root-cause report)
  diagnosis ─► facts F1..Fm, metrics, scope (products, orders issuing the material in each period)
  dense query = classification label + material-master descriptions of the material and products (no codes or figures)
  anchors     = material + products + order numbers
  window  = [earlier period start − 90 days, later period end]   (ECN and QUALITY only; CASE undated)
  per source type (ECN, QUALITY, CASE):
    ├─ dense : vector search, top 20, metadata filter sourceType + effectiveDay
    └─ exact : chunks mentioning anchors, ranked by distinct matches then recency, top 20
    Reciprocal Rank Fusion (k = 60) ─► top 3
  call 1: findings ─► context[], hypotheses[] ─► citation validation
  call 2: summary of validated findings only
  ─► JSON report
```

- Dense retrieval finds documents describing the same problem in different words. Its query deliberately omits codes and figures: in evaluation, codes and figures made code-heavy documents look similar regardless of meaning, while material-master names and specifications supplied the words documents use (see [Validation](#validation)).
- Exact retrieval finds documents citing the affected part, product or order number, which embeddings represent poorly. Code candidates are tokens containing a digit or hyphen that exist in `material_master`, `bill_of_material` or `inventory_usage`.
- Reciprocal Rank Fusion merges both rankings without score calibration; whether it beats each retriever alone is measured, not assumed.
- Retrieving per source type stops one type crowding out the others and makes an empty type visible.

### Embedding model constraints

The configured default `sentence-transformers/all-MiniLM-L6-v2` produces 384-dimensional vectors (`vector(384)`). The packaged encoder rejects inputs longer than the model's position limit instead of truncating, so chunks are bounded at 200 tokens using the model's own tokenizer. The model is trained mainly on English; non-English documents need a different model chosen before ingestion. Each document stores the embedding signature, and retrieval reports a mismatch when the current model differs.

## Report JSON contract

`InvestigationReport` (decimals are plain strings):

```
inventoryId, periods {actualStart, actualEnd, comparableStart, comparableEnd}
classification: {category, label, certainty}
summary:        string | null
metrics:        [{label: Quantity|Total cost|Unit cost, comparison, actual, change}]   // null when absent
facts:          [{id: "F1", text}]
scope:          {material, products[], orders[{orderNo, date, product, period: COMPARISON|ACTUAL}]}
timeline:       [{date, sourceId, sourceType, title, position: BEFORE|COMPARISON|BETWEEN|ACTUAL|AFTER}]
context:        [{sourceType, text, citations[]}]
hypotheses:     [{text, citations[], nextCheck, certainty: "Needs verification"}]
sources:        [{id: "S1", documentId, sourceType, title, effectiveDate, excerpt, retrievedBy: DENSE|EXACT|BOTH}]
messages:       [string]   // empty source types, degraded retrieval, removed items, model errors
removedItemCount: int
```

Degradation: without the embedding runtime, retrieval uses exact matching only; with an unavailable chat model, facts, metrics, scope, timeline and sources are still returned. Each case is stated in `messages`. Uploading requires the embedding runtime (HTTP 503 otherwise).

## Frontend view (deferred)

Not implemented in this version, by decision, because the frontend will be rebuilt. Suggested layout for the rebuild, using only the JSON contract: header with classification badge and summary; metric cards; a timeline strip with the two periods as bands and sources as dots; three groups (verified facts, documented context, hypotheses with next checks) with clickable `Fn`/`Sn` citation chips; source cards; a footer for `messages`; and a knowledge-base tab for upload, list and delete.

## Evaluation

The opt-in `RagEvaluationTests` uses the synthetic fixture `backend/src/test/resources/investigation/evaluation-fixture.json` (36 documents, 12 per type, including out-of-window and distractor documents; 24 labelled anomalies with relevant documents and expected-cause keywords).

| Metric | Definition | Acceptance criterion |
| --- | --- | --- |
| Recall@3 | Relevant documents retrieved in the top 3 of their source type ÷ relevant documents, per type and overall | Hybrid overall ≥ 0.80 and not below dense-only or exact-only |
| MRR | Mean reciprocal rank of the first relevant document per (anomaly, source type) pair | Reported |
| Citation validity | Generated items kept ÷ generated items | ≥ 0.90 |
| Hypothesis hit rate | Anomalies whose first three hypotheses contain an expected keyword ÷ anomalies | ≥ 0.60 |

These criteria are the acceptance thresholds for this prototype; they are not derived from an external standard. The keyword hit rate is a coarse proxy for hypothesis quality.

Run from `backend/` with the embedding runtime installed (and, for generation, llama.cpp on the configured address):

```sh
./mvnw test -Dtest=RagEvaluationTests -Drag.evaluation=true [-Drag.evaluation.llm=true] \
  -Dmargin.embedding.python=<runtime>/venv/bin/python \
  -Dmargin.embedding.model=sentence-transformers/all-MiniLM-L6-v2 \
  -Dmargin.embedding.revision=1110a243fdf4706b3f48f1d95db1a4f5529b4d41 \
  -Drag.evaluation.output=target/rag-evaluation.md
```

## Validation

Ordinary `./mvnw test` covers parsing, chunk bounds, code extraction, RRF ordering, date and type filters, exact-only fallback, deletion, citation validation, decimal metrics, timeline boundaries, degradation and the enabled/disabled isolation, with a deterministic fake embedding provider, an in-memory vector store and H2 (`classpath:db/investigation-h2`, a copy of the migration without pgvector). The opt-in `InvestigationPostgresSmokeTests` exercises the real migrations, pgvector store and metadata filters on PostgreSQL.

Results of 2026-10-19 ([details](../validation/rag-investigation-2026-10-19/README.md)): all default tests pass; the PostgreSQL smoke test passes on the project's `postgres` image; the evaluation passes with hybrid recall@3 0.891 (dense 0.844, exact 0.656), MRR 0.800, citation validity 0.990 and expected cause in the top three hypotheses for 23 of 24 anomalies, using MiniLM and a local Qwen3.5-4B model. The query design and output bounds were changed in response to failures on the same fixture, so there is no held-out measurement.

## Known limitations

- Materials feeding several products have aggregated graph quantities, so they are anchored only at material level.
- One anomaly per report; no export or saved history.
- No PDF parsing (would need Apache PDFBox).
- The default embedding model is English-oriented.
- Citation validation removes unsupported items but cannot judge whether a valid citation is relevant; analyst review remains required.
- If ingestion fails after the raw file is stored, the raw object is not removed.
- The evaluation corpus is small and synthetic; results indicate behaviour on this fixture only.
