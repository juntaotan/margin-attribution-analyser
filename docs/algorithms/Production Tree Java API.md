# Production tree Java API

This stage implements the revised two-pass [production-ID workflow](<FGW_Algorithm_Design.md>) and [tree/evidence rules](<FGW_Build_Production_Trees.md>) using the existing serial Java FGW engine. The [five-step specification](<FGW_Implementation_Five_Steps.md>) and [fixed numerical fixture](<FGW_Test_Case.md>) supply the mathematical requirements.

## Endpoints

| Method and path | Result |
| --- | --- |
| `POST /api/v1/analysis/production-trees/inspect` | Selected actual trees, preceding graph scope, accepted/excluded issues and allocations, decimal costs, output metadata, final CSR and completeness. |
| `POST /api/v1/analysis/production-trees/compare` | The same inspection evidence, eligibility diagnostics and, when eligible, the existing Java FGW result. |

Both endpoints run synchronously. Database access uses a read-only repeatable-read transaction. An FGW session is constructed and consumed within the comparison call on its owning thread. No application-wide session, executor or result cache is introduced. Existing `algorithm/TreeBuilder`, analysis endpoints, database schema and `FgwEngine` are unchanged.

Malformed requests, missing/unverified preceding graphs, invalid CSR/root identities, conflicting or overdrawn processing pools, invalid selection periods, invalid source shares, invalid FGW configuration and infeasible initial matrices return HTTP 400 with `message`. Missing/ambiguous identities and semantically incomplete comparisons return HTTP 200 with explicit statuses and inspection evidence. They do not produce a solver result. Numerical failures return the engine's `NUMERICAL_FAILURE` and diagnostics, with `converged=false`.

## Identity and dataset scope

The confirmed mapping is:

```text
productionId = inventory_usage.order_no
productId = inventory_usage.product_no
issueLineId = inventory_usage.id + "@" + inventory_usage.date
issueDocumentId = inventory_usage.movement_no
```

The composite issue ID preserves both database key fields. Documents, equal amounts and equal materials never identify duplicate rows. Each selected ID must have exactly one receiving product; otherwise `AMBIGUOUS_PRODUCTION_PRODUCT` rejects it. Blank or absent IDs yield `MISSING_PRODUCTION_ID`.

`evidence.namespace` explicitly identifies the source dataset/organisation. Native instance IDs use length-prefixed namespace and production ID components to avoid delimiter collisions. The namespace labels the connected warehouse; it is **not an organisation filter**. The current warehouse has no organisation column. Keep separately scoped organisations in separately scoped datasets until a schema/import contract can distinguish their rows. Do not mix organisations with colliding order numbers and expect this label to separate their records.

## Inspection request

```json
{
  "productionIds": ["P1", "P2"],
  "productIds": null,
  "startDate": null,
  "endDate": null,
  "abnormalPaths": null,
  "actualGraph": {
    "graphId": "actual-stage-snapshot",
    "namespace": "example-organisation",
    "direction": "PRODUCT_TO_MATERIAL",
    "verifiedActual": true,
    "nodes": [
      {"nodeId": "root-A", "productId": "A"},
      {"nodeId": "material-B", "productId": "B"},
      {"nodeId": "material-C", "productId": "C"}
    ],
    "rowOffsets": [0, 2, 2, 2],
    "colIndices": [1, 2],
    "edgeIds": ["A-B", "A-C"],
    "roots": {"P1": "root-A", "P2": "root-A"},
    "completeCoverage": true,
    "coverageStart": null,
    "coverageEnd": null
  },
  "evidence": {
    "namespace": "example-organisation",
    "scope": "DIRECT_CONSUMPTION",
    "completeProductionIds": ["P1", "P2"],
    "currency": "NZD",
    "materialCostScope": "recorded-material-issues",
    "totalCostScope": null,
    "processingSources": [],
    "processingAllocations": [],
    "issues": {
      "101@2026-01-01": {"unit": "kg", "kind": "RAW"}
    },
    "outputs": {
      "P1": {
        "quantityInterpretation": "REPEATED_INSTANCE_TOTAL",
        "costInterpretation": "REPEATED_INSTANCE_TOTAL",
        "unit": "each",
        "comparableCostScope": true,
        "receipts": []
      },
      "P2": {
        "quantityInterpretation": "REPEATED_INSTANCE_TOTAL",
        "costInterpretation": "REPEATED_INSTANCE_TOTAL",
        "unit": "each",
        "comparableCostScope": true,
        "receipts": []
      }
    }
  }
}
```

The issue ID in this example is illustrative: replace it with an actual returned composite identity, and supply evidence for every contributing row. An initial inspection can omit issue/output evidence to discover source identities and explicit missing-input statuses. Then inspect again with verified evidence before supplying embeddings. Evidence changes can change occurrence grouping and IDs; use the final inspection's IDs.

Selection modes can be combined:

- `productionIds` selects IDs directly, deduplicating repeated selections. Dates do not truncate explicitly selected IDs.
- `productIds` selects production IDs for those products, optionally restricted to the inclusive selection period.
- Dates without other selectors select all production instances with records in that period.
- `abnormalPaths` accepts the existing `ReconciliationPathEntry` contract from reconciliation endpoints. Its nodes seed candidate products/materials, then selection follows directed **recorded inventory-usage relationships** to receiving products. All contributing `abnormal-path:<index>` references survive deduplication. No new detector or anomaly threshold is introduced.

After selection, every available record for each selected `order_no` is retrieved, including records outside the period. Full-lineage upstream IDs are loaded without a date restriction too. `selection`, `datasetScope` and `identifierMapping` record this scope in the response. Selection evidence identifies candidates, not proven causes.

`completeProductionIds` is an explicit caller declaration that imported source coverage is complete for those instances, including expanded upstream instances. The database cannot prove that an import contains every source transaction. Absent declarations yield `INCOMPLETE_IMPORTED_COVERAGE` and block comparison.

## Required preceding-stage graph and two passes

`actualGraph` is mandatory for inspection and for the nested comparison request. Earlier request bodies without this field now return `MISSING_PRECEDING_ACTUAL_GRAPH` (HTTP 400). Existing material amount/quantity response fields keep their original meanings. No frontend caller of these production-tree endpoints exists in this checkout.

The graph carries actual manufacturing adjacency, stable graph/node/edge identity, source namespace, explicit production-ID roots, semantic direction and import/period coverage. The existing `CsrGraph` convention is `MATERIAL_TO_PRODUCT`: use its `Node.inventoryId` as product codes, its `offset`/`successors` arrays as `rowOffsets`/`colIndices`, and the preceding analysis's `StreamCsrGraph` IDs as stable node/edge identities. Supply the selected ID-to-root mapping and coverage declarations explicitly. No API synthesises this input from the completed tree or assumes a BOM snapshot is actual evidence. `PRODUCT_TO_MATERIAL` input is also supported; the traversal transposes adjacency when required while retaining edge identity.

Validation requires aligned offsets/successors/edge IDs, monotone bounded offsets, valid successors, distinct node/edge IDs and compatible selected roots. Duplicate parallel CSR entries require disambiguated manufacturing positions rather than repeated identical successors. A product code may appear at distinct graph nodes; `IssueEvidence.graphEdgeId` disambiguates those positions. An ambiguous relationship is excluded with `AMBIGUOUS_GRAPH_RELATIONSHIP` instead of merging graph positions.

The first pass records per-root `topDownScope.permittedNodes`, `permittedEdges`, every root-to-terminal `paths`, terminals and bottom-up node order, alongside the complete original graph. Direct branches and deeper branches are both retained. Cycles/self-consumption remain explicit incomplete scope. `completeCoverage=false` yields `INCOMPLETE_GRAPH_COVERAGE`. Bounded coverage that omits an accepted issue's date yields `INCOMPLETE_GRAPH_PERIOD_COVERAGE`; dates select instances without truncating retrieval. Null coverage dates declare unbounded source coverage, independently of the completeness flag.

The second pass queries **all** available terminal consumption first, then candidate material-to-product relationships in dependency order. Queries are cached within one request and never constrained by selection dates. Complete receiving/source instances are loaded by `order_no`. Acceptance requires receiver membership, the actual permitted edge, a valid position path and the explicit receiving/source production relationship. Node membership alone does not accept a cross-edge or another batch of the same SKU. Actual selected-instance issues absent from the graph remain excluded/unmatched and mark `INCOMPLETE_GRAPH_SCOPE`. Other receivers outside the selected path remain candidate evidence without invalidating an otherwise complete receiving tree.

`sourceIssues` now includes original candidate and complete-instance records, including exclusions. `issueDecisions` explains each accepted occurrence projection or rejection, with graph ID, root instance identity, edge ID, original issue identity and exact decimal flow values. Out-of-scope receivers yield `RECEIVER_OUTSIDE_SCOPE`; prohibited connections yield `RELATIONSHIP_OUTSIDE_SCOPE`; unused compatible product connections yield `RECEIVING_OR_SOURCE_PRODUCTION_OUTSIDE_ROOT_PATH`. Equal documents are never deduplication keys. Shared source issues appear once in original evidence, with separate explicit projections at each accepted occurrence.

## Issue, position and upstream evidence

Issue evidence is keyed by the composite warehouse identity and contains:

| Field | Meaning |
| --- | --- |
| `unit` | Declared issue unit; absent units are reported and never mixed as known quantities. |
| `kind` | `RAW`, `INTERMEDIATE` or `UNKNOWN`; absent classification is `UNKNOWN`. |
| `receivingIssueLineId` | Optional explicit receiving intermediate occurrence, identified by one of its source issue IDs within the same production instance. Null attaches the issue directly to the root. |
| `sourceProductionId` | Explicit supplying production ID for the consumed occurrence. No SKU/time/FIFO inference occurs. |
| `sourceShare` | Explicit fraction in `(0,1]` of homogeneous supplying output represented by this occurrence. Never inferred from quantities. |
| `graphEdgeId` | Optional preceding graph edge identity, required when product codes alone match several positions. |
| `reversalOfIssueLineId` | Preserved explicit reversal link. No netting rule is specified by this stage, so linked/negative returns remain flagged and ineligible. |

Aggregation requires matching receiving position, material, unit, classification and explicit source/share evidence. Different source instances, shares or receiving positions retain separate occurrences. Unknown units keep row-specific occurrences. Issue-document splitting does not create manufacturing nodes.

For example, `{"unit":"each","kind":"INTERMEDIATE","sourceProductionId":"UP1","sourceShare":0.2}` identifies a consumed homogeneous share. Share evidence describes the **whole aggregated occurrence**, not a fraction to add once per contributing row. If multiple issue rows make up that occurrence, their declared relationship/share must agree. Distinct share evidence retains distinct occurrences.

`DIRECT_CONSUMPTION` includes receiving-instance actual issues and any explicitly recorded internal receiving positions. It does not load supplying instances. Known but unexpanded sources yield `UPSTREAM_NOT_EXPANDED_AT_DIRECT_SCOPE`. Intermediates with no source/recorded input relationships yield `UNRESOLVED_SOURCE_PRODUCTION`. Such boundaries can still be compared at direct scope when direct records are complete; their multilevel lineage is not declared complete.

`FULL_LINEAGE` expands only a named upstream instance with a declared share and matching product. Multiplication of source quantities/amounts and nested shares uses exact `BigDecimal` arithmetic. Projected source branches are occurrence-specific. Missing shares/instances, product mismatches, cycles, missing classification, incomplete coverage and unresolved intermediates prevent complete lineage. Conflicting local-step and external-source input descriptions are excluded with explicit relationship statuses rather than duplicated. Explicit source shares inconsistent with same-unit output quantities yield `SOURCE_SHARE_QUANTITY_CONFLICT`; unsupported source/output units yield `INCOMPATIBLE_SOURCE_OUTPUT_UNIT`. Overlapping projections exceeding an entire supplying batch return HTTP 400. No unsupported branch is added from BOM, receipt layers or chronological ordering.

## Output and financial evidence

Quantity and output-cost interpretations are independent:

- `REPEATED_INSTANCE_TOTAL` confirms that warehouse `product_num` or `product_total_cost` values represent repeated instance totals. Equal non-null totals count once; conflicting totals are reported and unresolved. Equality alone is not treated as semantic confirmation.
- `RECEIPTS` supplies authoritative output receipt records with `receiptId`, `quantity` and `cost`. Distinct receipt IDs are summed even when amounts agree. Repeated identical receipt identities count once; conflicting records for one identity yield an output conflict. Missing identities/values remain explicit.
- `UNVERIFIED` or absent interpretations preserve an unresolved output status.

Warehouse issue quantities and amounts are never overridden by this evidence. All original candidate and loaded rows are retained in `sourceIssues`, including date/document identity and unprojected decimal values. Nodes and edges retain each contributing issue reference, projected quantity/amount, receiving production and upstream ID/share. Source output quantity/cost is separate occurrence metadata.

`recordedMaterialAmount` sums non-overlapping issues received at the root. An intermediate's upstream inputs, whether linked local positions or expanded source instances, do not add another financial contribution to that subtotal. The root node's amount repeats this labelled subtotal; do not add it to its children. Missing inputs retain flags even when a known subtotal exists; an entirely missing subtotal is null, not zero. Node, edge and output decimal totals are strings for exact consumption by API clients; original `sourceIssues` decimal JSON numbers require a decimal-aware parser.

`recordedOutputCost` is separate. When `comparableCostScope=true` and both totals are complete, `outputCostDifference = recordedOutputCost - recordedMaterialAmount`. Nonzero differences yield `OUTPUT_COST_DISCREPANCY`. No overhead or labour amount is invented to force equality. Missing/unverified output cost remains explicit without changing FGW inputs; an output-cost conflict is ineligible.

## Node totals and referenced edge processing costs

Inspection of warehouse contracts found no production labour/overhead allocations: `sales_details.total_cost` is sold-product valuation without receiving production/relationship allocation identity. The API therefore accepts explicit external evidence; no new migration or processing-cost inference is used.

`evidence.processingSources` records original production-level pools with `sourceId`, receiving `productionId`, `currency`, valuation `costScope`, and independent decimal `labor`/`overhead` amounts. `processingAllocations` contains explicit splits with distinct `allocationId`, referenced `sourceId`, receiving `productionId` and `receivingIssueLineId` position, exact grouped `issueLineIds`, the same currency/scope, a nonblank `allocationBasis`, and decimal `labor`/`overhead`. A target must name exactly one grouped incoming occurrence; several share projections of that original occurrence are permitted and scaled separately. A missing target/basis is retained as unallocated evidence. Nothing automatically distributes a production total over edges.

For example, a pool `{"sourceId":"labour-overhead-P1","productionId":"P1","currency":"NZD","costScope":"manufacturing","labor":12,"overhead":8}` can have two explicit allocations of labour/overhead `9/6` and `3/2` to separate incoming material groups. The original pool is counted once. Copying `12/8` onto both edges fails with `PROCESSING_ALLOCATION_EXCEEDS_SOURCE_TOTAL`. Repeated identical allocation identities count once; conflicting identities fail. Missing pools, unsupported bases, unallocated residuals and incompatible scopes remain visible in `processingDecisions`, with original pool/allocation values and projected decimal components. Original numeric JSON evidence requires a decimal-aware parser.

The material valuation scope and `totalCostScope` must use the same explicitly compatible scope label to declare a complete combined total. Pools, allocations and output comparisons must also have matching declared currency/scope. Missing or incompatible declarations retain known amounts and prevent a complete node total; incompatible processing amounts are excluded from accepted subtotals. Zero labour and overhead must be supplied explicitly. Null/absent amounts are missing, never zero.

Nodes expose `materialSubtotal`, `laborCost`, `overheadCost`, `knownTotalCost`, nullable complete `totalCost`, currency/scope and `costComplete`. Receivers are finalised after all incoming contributions. Their total is **incoming recorded material valuations plus incoming edge labour and overhead**. Child totals are summaries and are never added again. Purchased raw-material leaves use their attributable issue valuation and require no invented manufacturing step. Produced/unknown boundaries retain known valuation with incomplete internal totals.

Edges keep material `quantity` and `amount` unchanged, adding separately named `laborCost`, `overheadCost`, `processingAllocationIds` and the permitted `graphEdgeId`. `quantityComplete`, `materialAmountComplete` and `processingComplete` describe that receiving flow independently of the material node's internal source-cost completeness. Full upstream original evidence and output valuation remain separate from projected receiving portions. An expanded source occurrence exposes `sourceTotalCost` (full source), `projectedSourceTotalCost` (its complete attributable breakdown), and `sourceValuationDifference = receiving issue amount - projected source total`. Independent complete source breakdowns remain available when another receiver has incomplete processing evidence.

Root fields repeat the root summaries as `calculatedTotalCost`, `knownTotalCost`, `laborCost`, `overheadCost` and root `costComplete`; each node's completeness remains separately available. `totalOutputCostDifference = recordedOutputCost - calculatedTotalCost` is new and requires complete root costs, confirmed output interpretation, `comparableCostScope=true`, and explicit matching `OutputEvidence.currency`/`costScope`. It never changes the legacy **material-only** `outputCostDifference` or `OUTPUT_COST_DISCREPANCY`. Nonzero total differences use `TOTAL_OUTPUT_COST_DISCREPANCY`. No residual is invented to balance either comparison.

In the fixed two-pass fixture, S's full source cost is 120, its 0.2 projection is 24 (`20 + 2.4 + 1.6`), and the actual downstream issue valuation is 30. The source valuation difference is 6. The root total is 40 (`30 + 4 + 6`), with a total output difference of zero and the preserved material-only difference of 10. Excluded 990/96 amounts contribute nothing. Missing root labour leaves a known subtotal of 36 and a null complete total; an explicit zero processing allocation remains distinguishable.

## Comparison request and response

Submit a `ProductionComparisonRequest` containing:

| Field | Contract |
| --- | --- |
| `trees` | The inspection request selecting both requested IDs and declaring their common scope/evidence. Trees are rebuilt from current database records. |
| `leftProductionId`, `rightProductionId` | Different receiving IDs of the same product and namespace. |
| `embeddings` | Object keyed by final inspection `occurrenceId`; each value is `{"version":"model-version","values":[...]}` with exactly 384 finite caller-supplied components. Include the root's own vector. |
| `config` | Optional existing `FgwEngine.Config`; absent fields use its defaults. No GPU settings are added. |
| `initialMatrices` | Optional feasible row-major `[leftNodeCount * rightNodeCount]` transport matrices. Empty/absent means one uniform outer-product start. Otherwise only the explicit starts are evaluated. |

The existing endpoint consumes caller-supplied occurrence vectors; it performs no model inference, neighbourhood aggregation, padding or zero-vector substitution. The proposed independent workflow prepares [twelve-field contextual vectors](<Embedding_Implementation.md#occurrence-context-embedding-preparation>) after final tree construction, encodes the complete context into one 384-dimensional vector, and retains a separate manifest containing input hashes and model/template/normalisation/precision provenance. It does not concatenate multiple 384-dimensional blocks. This is a design extension: the existing endpoint checks dimensions, finite values and version compatibility but does not verify the twelve fields or their provenance. `missingVectors` lists every absent occurrence vector. Wrong lengths, nonfinite values, absent versions or incompatible versions yield explicit statuses and no numerical execution.

Eligibility requires different instances of the same product and matching topology scopes. Incomplete recorded quantities, amounts, units, output quantity semantics, identity/relationship evidence or coverage prevent accepted direct comparison. Unresolved source lineage additionally prevents full-lineage comparison. Cost completeness is independent of topology eligibility: accepted mathematical results add `INCOMPLETE_TOTAL_COST_EVIDENCE` if any occurrence has incomplete total-cost restoration, preserving all source/cost evidence alongside the result. This qualification does not alter solver inputs or block otherwise complete topology comparison. Numerical configuration and initial-matrix validation remain the existing engine's responsibility.

`result` contains the engine's row/column counts, `left`/`right` source mappings, fixed `p`/`q`, `bestStart` and every start's correspondence, separate material/structure/FGW costs, applied-update count, terminal status, fresh gap, scalar OT certificates and diagnostic. `converged` describes the selected start and is true only for `FW_GAP_REACHED`. `COST_STALLED` and `MAX_ITERATIONS` are not convergence. With `captureTrace=true`, distance and intermediate matrices are also returned.

For a correspondence entry `P[i * result.columns + j]`, join `result.left.nodes[i].occurrenceId` and `result.right.nodes[j].occurrenceId` to the inspection nodes. Inspection edges/CSR identify their receiving branches, source issues and projected shares. Native `NodeSource.productionOrderId` holds that occurrence's receiving production ID; native instance IDs preserve the namespace. `fifoAllocationIds` is always empty.

The objective remains squared Euclidean node attribute distance, undirected unweighted shortest-path structural distance, uniform fixed node weights, exact balanced linear OT and the existing Frank–Wolfe/quadratic-line-search calculation. Defaults are `alpha=0.8`, `maxIterations=200`, `tolAbs=1e-6`, `tolRel=1e-5`, `tolGapAbs=1e-6`, `tolGapRel=1e-5`, `costScaleFloor=1e-12`. With fixed supplied vectors, recorded quantity/price metadata changes alone do not affect this objective. Regenerated twelve-field context containing unit cost or neighbours can change the attribute distance indirectly; that distance is not a currency amount. A cost or zero fresh gap is not proof of a production cause or global optimality. Existing validation evidence below covers the supplied-vector implementation, not model generation or contextual model quality.

## Validation and reproducibility

The earlier Java numerical stage is described in [its task record](../tasks/2026-10-11-183228-java-production-tree-fgw.md). Its raw validation artefacts are absent from this checkout; use the fresh evidence below for the current implementation.

The revised tree requirement-by-requirement results are in [the two-pass validation report](../validation/production-tree-two-pass-2026-10-11/validation.md), with fresh unchanged [FGW independent CPU/POT evidence](../validation/production-tree-two-pass-2026-10-11/fgw-validation.md).

From the repository root, with intentionally installed Java 21:

```sh
(cd backend && ./mvnw --batch-mode --no-transfer-progress test)

mkdir -p /tmp/production-tree-numerical
java -cp backend/target/classes:backend/target/test-classes \
  dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwFixtures \
  /tmp/production-tree-numerical/java.json
python3 -m venv /tmp/production-tree-reference
/tmp/production-tree-reference/bin/pip install -r benchmark/fgw/requirements.txt
/tmp/production-tree-reference/bin/python benchmark/fgw/validate.py \
  --native-report /tmp/production-tree-numerical/java.json \
  --output /tmp/production-tree-numerical/validation.md
```

The existing validator's `native-report` argument accepts the Java driver's report; evidence records `JAVA_SERIAL`. Python is an independent numerical test oracle, not a production dependency. The validator checks the prescribed matrices/costs, marginals, finite/nonnegative transport, independent tensor objective and exact CPU LP, directional derivatives, line-search monotonicity, ordering and POT at FP64 tolerance `1e-8`. CUDA being unverified in that report is expected for this explicitly Java-only stage.

Docker remains the project default alternative for backend testing; use the Java 21 Maven command in `AGENTS.md`. The new integration slice explicitly uses an isolated H2 PostgreSQL-mode database with rollback, and tests actual JPA queries and real FGW through MockMvc. It does not establish PostgreSQL partition/migration correctness or a deployed network/API environment. No live warehouse writes, schema changes, GPU binding, embedding generation, automatic cause attribution or production-readiness claim are part of this stage. The synchronous engine uses dense matrices; production-scale latency/memory and persisted external lineage/coverage evidence remain unvalidated.
