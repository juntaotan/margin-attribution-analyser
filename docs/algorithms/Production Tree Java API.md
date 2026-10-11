# Production tree Java API

This stage implements [the production-ID workflow](<Restore Product Production Trees and Compare with FGW.md>) and [tree/evidence rules](<Build Product Production Trees by Production ID>) using the existing serial Java FGW engine. The historical specification links point to filenames absent from this checkout; the present [five-step specification](<FGW Implementation - Implement Standard FGW in Five Steps.md>) and [fixed numerical fixture](<FGW Implementation - Test Case.md>) supply the mathematical requirements.

## Endpoints

| Method and path | Result |
| --- | --- |
| `POST /api/v1/analysis/production-trees/inspect` | Selected actual trees, original source issues, output metadata, CSR, completeness and rejected identities. |
| `POST /api/v1/analysis/production-trees/compare` | The same inspection evidence, eligibility diagnostics and, when eligible, the existing Java FGW result. |

Both endpoints run synchronously. Database access uses a read-only repeatable-read transaction. An FGW session is constructed and consumed within the comparison call on its owning thread. No application-wide session, executor or result cache is introduced. Existing `algorithm/TreeBuilder`, analysis endpoints, database schema and `FgwEngine` are unchanged.

Malformed requests, invalid selection periods, invalid source shares, invalid FGW configuration and infeasible initial matrices return HTTP 400 with `message`. Missing/ambiguous identities and semantically incomplete comparisons return HTTP 200 with explicit statuses and inspection evidence. They do not produce a solver result. Numerical failures return the engine's `NUMERICAL_FAILURE` and diagnostics, with `converged=false`.

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
  "evidence": {
    "namespace": "example-organisation",
    "scope": "DIRECT_CONSUMPTION",
    "completeProductionIds": ["P1", "P2"],
    "currency": "NZD",
    "materialCostScope": "recorded-material-issues",
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

## Issue, position and upstream evidence

Issue evidence is keyed by the composite warehouse identity and contains:

| Field | Meaning |
| --- | --- |
| `unit` | Declared issue unit; absent units are reported and never mixed as known quantities. |
| `kind` | `RAW`, `INTERMEDIATE` or `UNKNOWN`; absent classification is `UNKNOWN`. |
| `receivingIssueLineId` | Optional explicit receiving intermediate occurrence, identified by one of its source issue IDs within the same production instance. Null attaches the issue directly to the root. |
| `sourceProductionId` | Explicit supplying production ID for the consumed occurrence. No SKU/time/FIFO inference occurs. |
| `sourceShare` | Explicit fraction in `(0,1]` of homogeneous supplying output represented by this occurrence. Never inferred from quantities. |
| `reversalOfIssueLineId` | Preserved explicit reversal link. No netting rule is specified by this stage, so linked/negative returns remain flagged and ineligible. |

Aggregation requires matching receiving position, material, unit, classification and explicit source/share evidence. Different source instances, shares or receiving positions retain separate occurrences. Unknown units keep row-specific occurrences. Issue-document splitting does not create manufacturing nodes.

For example, `{"unit":"each","kind":"INTERMEDIATE","sourceProductionId":"UP1","sourceShare":0.2}` identifies a consumed homogeneous share. Share evidence describes the **whole aggregated occurrence**, not a fraction to add once per contributing row. If multiple issue rows make up that occurrence, their declared relationship/share must agree. Distinct share evidence retains distinct occurrences.

`DIRECT_CONSUMPTION` includes receiving-instance actual issues and any explicitly recorded internal receiving positions. It does not load supplying instances. Known but unexpanded sources yield `UPSTREAM_NOT_EXPANDED_AT_DIRECT_SCOPE`. Intermediates with no source/recorded input relationships yield `UNRESOLVED_SOURCE_PRODUCTION`. Such boundaries can still be compared at direct scope when direct records are complete; their multilevel lineage is not declared complete.

`FULL_LINEAGE` expands only a named upstream instance with a declared share and matching product. Multiplication of source quantities/amounts and nested shares uses exact `BigDecimal` arithmetic. Projected source branches are occurrence-specific. Missing shares/instances, product mismatches, cycles, missing classification, incomplete coverage and unresolved intermediates prevent complete lineage. Conflicting local-step and external-source input descriptions are flagged rather than duplicated. No unsupported branch is added from BOM, receipt layers or chronological ordering.

## Output and financial evidence

Quantity and output-cost interpretations are independent:

- `REPEATED_INSTANCE_TOTAL` confirms that warehouse `product_num` or `product_total_cost` values represent repeated instance totals. Equal non-null totals count once; conflicting totals are reported and unresolved. Equality alone is not treated as semantic confirmation.
- `RECEIPTS` supplies authoritative output receipt records with `receiptId`, `quantity` and `cost`. Distinct receipt IDs are summed even when amounts agree. Repeated identical receipt identities count once; conflicting records for one identity yield an output conflict. Missing identities/values remain explicit.
- `UNVERIFIED` or absent interpretations preserve an unresolved output status.

Warehouse issue quantities and amounts are never overridden by this evidence. All original loaded rows are retained in `sourceIssues`, including date/document identity and unprojected decimal values. Nodes and edges retain each contributing issue reference, projected quantity/amount, receiving production and upstream ID/share. Source output quantity/cost is separate occurrence metadata.

`recordedMaterialAmount` sums non-overlapping issues received at the root. An intermediate's upstream inputs, whether linked local positions or expanded source instances, do not add another financial contribution to that subtotal. The root node's amount repeats this labelled subtotal; do not add it to its children. Missing inputs retain flags even when a known subtotal exists; an entirely missing subtotal is null, not zero. Node, edge and output decimal totals are strings for exact consumption by API clients; original `sourceIssues` decimal JSON numbers require a decimal-aware parser.

`recordedOutputCost` is separate. When `comparableCostScope=true` and both totals are complete, `outputCostDifference = recordedOutputCost - recordedMaterialAmount`. Nonzero differences yield `OUTPUT_COST_DISCREPANCY`. No overhead or labour amount is invented to force equality. Missing/unverified output cost remains explicit without changing FGW inputs; an output-cost conflict is ineligible.

## Comparison request and response

Submit a `ProductionComparisonRequest` containing:

| Field | Contract |
| --- | --- |
| `trees` | The inspection request selecting both requested IDs and declaring their common scope/evidence. Trees are rebuilt from current database records. |
| `leftProductionId`, `rightProductionId` | Different receiving IDs of the same product and namespace. |
| `embeddings` | Object keyed by final inspection `occurrenceId`; each value is `{"version":"model-version","values":[...]}` with exactly 384 finite caller-supplied components. Include the root's own vector. |
| `config` | Optional existing `FgwEngine.Config`; absent fields use its defaults. No GPU settings are added. |
| `initialMatrices` | Optional feasible row-major `[leftNodeCount * rightNodeCount]` transport matrices. Empty/absent means one uniform outer-product start. Otherwise only the explicit starts are evaluated. |

Use the node's own material-content vector. No neighbourhood embedding, padding, zero-vector substitution or embedding generation is performed. `missingVectors` lists every absent occurrence vector. Wrong lengths, nonfinite values, absent versions or incompatible versions yield explicit statuses and no numerical execution.

Eligibility requires different instances of the same product and matching topology scopes. Incomplete recorded quantities, amounts, units, output quantity semantics, identity/relationship evidence or coverage prevent accepted direct comparison. Unresolved source lineage additionally prevents full-lineage comparison. Numerical configuration and initial-matrix validation remain the existing engine's responsibility.

`result` contains the engine's row/column counts, `left`/`right` source mappings, fixed `p`/`q`, `bestStart` and every start's correspondence, separate material/structure/FGW costs, applied-update count, terminal status, fresh gap, scalar OT certificates and diagnostic. `converged` describes the selected start and is true only for `FW_GAP_REACHED`. `COST_STALLED` and `MAX_ITERATIONS` are not convergence. With `captureTrace=true`, distance and intermediate matrices are also returned.

For a correspondence entry `P[i * result.columns + j]`, join `result.left.nodes[i].occurrenceId` and `result.right.nodes[j].occurrenceId` to the inspection nodes. Inspection edges/CSR identify their receiving branches, source issues and projected shares. Native `NodeSource.productionOrderId` holds that occurrence's receiving production ID; native instance IDs preserve the namespace. `fifoAllocationIds` is always empty.

The objective remains squared Euclidean material distance, undirected unweighted shortest-path structural distance, uniform fixed node weights, exact balanced linear OT and the existing Frank–Wolfe/quadratic-line-search calculation. Defaults are `alpha=0.8`, `maxIterations=200`, `tolAbs=1e-6`, `tolRel=1e-5`, `tolGapAbs=1e-6`, `tolGapRel=1e-5`, `costScaleFloor=1e-12`. Recorded quantity/price changes alone do not affect this objective. A cost or zero fresh gap is not proof of a production cause or global optimality.

## Validation and reproducibility

Actual results are in [the task record](../tasks/2026-10-11-183228-java-production-tree-fgw.md), the [numerical PASS/FAIL report](../validation/production-tree-fgw-2026-10-11/validation.md), [independent/reference evidence](../validation/production-tree-fgw-2026-10-11/validation.json) and [Java engine evidence](../validation/production-tree-fgw-2026-10-11/java.json).

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
