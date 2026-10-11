# FGW production-tree construction

Child of [FGW algorithm design](FGW_Algorithm_Design.md). This document owns tree construction, identity/scope/cost evidence and tree acceptance tests. The parent owns stage boundaries; the main design owns embedding generation and full-pipeline orchestration.

The [tree test specification](FGW_Build_Production_Trees_Test_Case.md) owns the confirmed primary instance, fixed fixtures and acceptance requirements.

# Objective

Build one actual production tree for each selected `productionId` through two required passes:

1. Traverse the preceding stage's product-to-material CSR from the selected product down to the terminal nodes. Record the permitted nodes, relationships and complete paths.
2. Query all available actual issue records for those terminal materials, then traverse material-to-product relationships upwards. Reject receivers outside the recorded scope and use issue documents to establish the quantities and material amounts attributable to each accepted receiving production instance. Record total cost on nodes and labour/manufacturing overhead on edges.

A final CSR generated after grouping issues does not satisfy the required first pass. Compare different production IDs of the **same product** through [standard balanced FGW](<FGW_Implementation_Five_Steps.md>).

This specification supersedes issue-grouping-first and FIFO restoration. The [Java API reference](<Production Tree Java API.md>) records the implementation contract; use actual validation evidence to establish which requirements are implemented.

A production instance represents the recorded batch or order, including all its issue lines and output records. It does not represent a physical single unit or an issue document. Never merge separate production IDs solely because they produce the same product.

Do not perform FIFO allocation, infer batches from timestamps, estimate missing consumption with Bayesian methods, or substitute BOM quantities for actual consumption.

# Verified Database State and Identifier Mapping

An earlier task recorded a read-only inspection of the PostgreSQL database connected to the running backend on 11 October 2026. That historical inspection has not been repeated for this specification revision. `public.inventory_usage` contains:

```text
id, date, movement_no, product_no, product_num, product_total_cost,
order_no, material_no, material_num, material_total_cost
```

There is **no dedicated `productionId` or `production_id` column** in that table. The [initial migration](../../backend/src/main/resources/db/migration/V1__create_core_tables.sql), [Java entity](../../backend/src/main/java/dev/margintrace/margin_attribution_backend/warehouse/model/InventoryUsage.java) and [import mapping](../../backend/src/main/java/dev/margintrace/margin_attribution_backend/importation/mapping/SchemaMappingPresetCatalog.java) agree with that schema.

The user has confirmed that the existing `order_no` / `orderNo` **is the receiving production ID** for this workflow. Use that confirmed mapping directly; a dedicated new column or a further identifier-inference step is unnecessary:

```text
productionId = inventory_usage.order_no
productId = inventory_usage.product_no
issueLineId = (inventory_usage.id, inventory_usage.date)
issueDocumentId = inventory_usage.movement_no
```

The inspected dataset had six rows, four distinct nonblank production IDs, no ID associated with multiple products, and no conflicting non-null output quantities within a production/product group. The user supplies the identifier semantics; the queries validate the inspected schema and grouping consistency only.

Requirements:

- Record the confirmed identifier mapping and dataset scope. Do not rename the existing database field solely to introduce the logical name `productionId`.
- Require one receiving product per production ID. Return `AMBIGUOUS_PRODUCTION_PRODUCT` for multiple products, rather than silently splitting one ID into accepted trees or inventing a co-product allocation.
- Retain an explicit source-system/organisation namespace if IDs are not globally unique. Do not manufacture batch identity from dates or material numbers.
- Return `MISSING_PRODUCTION_ID` for absent or blank IDs. Do not use a movement number or generated row ID as a replacement production ID. Other source datasets must map their production identifiers explicitly into `order_no`.
- The inspected table has no explicit upstream source-production link for consumed semi-finished products. A receiving production ID alone does not identify the supplying batch. CSR reachability identifies candidate topology, not source-batch ownership.
- The listed issue columns contain no explicit labour or manufacturing-overhead allocation fields. Inspect existing cost sources and their contracts before implementation. If no suitable source exists, accept explicit, referenced cost-allocation evidence through the Java API. Do not infer processing costs from `product_total_cost - material_total_cost`, invent a distribution rule, or introduce a migration merely to rename an identifier.
- This specification describes subsequent implementation, not already implemented functionality. This documentation task does not authorise database writes or schema changes.

# Step 1: Select Instances and Record the Top-Down Scope

**Inputs:** The preceding stage's selected product codes and actual manufacturing CSR, explicit production-ID selection or existing abnormal-branch results, reporting scope, verified identifier mapping and source namespace.

**Outputs:** Selected production IDs, roots, permitted node set `S`, permitted edge/path set `E`, terminal nodes, source graph identity and scope/identity statuses.

**Computation:**

(1) Select requested production IDs directly, or reuse existing abnormal-branch discovery to identify candidate products and their production instances. Deduplicate by scoped production ID and preserve every contributing selection reference. Product-level detection identifies candidates, not proven causes. Require one receiving product per ID.

(2) Use dates to select instances. Subsequent retrieval must include all available records of each selected instance, even outside that selection period. Preserve the preceding graph's coverage and period separately; a graph missing relevant cross-period relationships must be reported as incomplete, not used to silently discard valid records.

(3) Accept or obtain the actual relationship CSR produced by the preceding stage. Validate root identity, node/index alignment, offsets, successors, edge identities, direction, coverage and cycles. The request or service contract must make the graph and selected roots explicit. Do not replace it with a CSR generated after the actual tree has already been built.

(4) Starting at each selected product root, traverse product-to-material adjacency down to every terminal. Record **every** visited node, permitted relationship and root-to-terminal path, including direct material branches alongside deeper branches. Keep separate scope evidence for each selected root. Detect cycles and incomplete relationships rather than claiming a complete terminal set.

(5) Name directions by their meaning, not by the word "reverse" alone. Existing Java `CsrGraph` edges normally point from material to product. For that convention, derive transposed adjacency for the top-down pass and use material-to-product adjacency for the upward pass. If the supplied CSR already points from product to material, invert those roles. Both passes must use consistent node/edge identities.

(6) Retain stable scoped identities. A product-level CSR node can identify a candidate material/product code but cannot establish a production-instance identity. Keep `(namespace, receiving productionId, material/product, manufacturing position, explicit supplying productionId)` where applicable when matching records and creating occurrences. Never merge different batches merely because their product codes match.

**Scope rule:** A receiver must be in `S`, its connecting relationship must be in `E`, and its occurrence must belong to the permitted root/production path. Node membership alone is insufficient when a shared material or cross-connection can reach several products.

An actual issue that cannot be matched to supplied actual topology must remain visible as excluded/unmatched evidence, with an incomplete-scope status. Do not add it as an accepted branch outside `S`/`E`, silently discard it, or claim a complete tree. A BOM-only graph is not a verified actual scope; unreliable BOM must not silently remove unexpected actual materials.

# Step 2: Collect Terminal Issues and Trace Actual Receivers Upwards

**Inputs:** Per-root `S`, `E` and terminals, actual issue/output records, units, explicit positions and supplying-production relationships.

**Outputs:** Accepted and excluded issue references with reasons, receiving-instance quantities/material amounts, source-link statuses and an actual occurrence graph restricted to the recorded scope.

**Computation:**

(1) Start at the recorded terminals and query all available material issue records for each terminal material in the declared source dataset. Preserve records for other receivers as candidate/exclusion evidence; querying all terminal consumption does not authorise attributing it all to the selected root. Retrieve complete selected/upstream production instances without truncating their records at the selection period.

(2) For each candidate upward receiver, first test membership in `S`. Reject out-of-scope receivers and record that exclusion. For receivers in `S`, also verify the permitted edge/path in `E`, receiving production identity and any required source-production link. If the scope's batch relationship is unresolved, retain boundary/unresolved evidence instead of guessing.

(3) Query or join the actual issue documents for the accepted material-to-receiver relationship. Match at least source namespace, `material_no`, `product_no` and receiving `order_no`, plus explicit manufacturing position and supplying production where available. Read actual `material_num` and `material_total_cost` to determine how much reached that specific receiver. Neither CSR membership nor the child's entire cost establishes the amount received.

(4) Preserve each original issue with identity `(id, date)` and document `movement_no`. Equal materials, amounts, dates or document numbers are not duplicates. Include a row once in a receiving contribution. Deduplicate overlapping terminal queries and shared traversal visits by source identity without merging genuinely different manufacturing occurrences.

(5) Aggregate only compatible receiving-position/material/unit/source-production contributions and retain every source reference. Use decimal arithmetic. Preserve missing quantities/amounts as missing, with known subtotals labelled as partial. Do not mix units or currencies.

(6) Repeat the same receiver-membership, edge/path and issue-document checks when moving from an intermediate to its next receiver. A shared intermediate's whole batch cost must not be copied into each parent. Product-level relationships are insufficient to choose which supplying batch contributed; require explicit upstream links and, when projecting a homogeneous source batch's breakdown, explicit consumed shares. Do not infer shares or batches from SKU, chronology or FIFO.

(7) Process accepted dependencies in bottom-up order. Accumulate all contributing children before finalising a receiver, including shallower terminals and converging/shared branches. Use occurrence-specific projections where the candidate structure is a DAG. Detect cycles, self-consumption, conflicting relationship evidence and missing links; do not silently truncate.

(8) If a terminal is an unresolved intermediate rather than a verified raw material, report its boundary status. Direct recorded consumption can remain inspectable, but this is not complete multilevel restoration. Reconcile reversals only with explicit transaction links and a declared netting rule; absent rules remain unresolved.

# Step 3: Record Node Total Costs and Edge Processing Costs

**Inputs:** Accepted actual receiving contributions, bottom-up dependencies, original issue values, referenced processing-cost allocations, output metadata and declared cost scopes.

**Outputs:** One rooted occurrence tree per selected production ID, node total costs, edge labour/overhead, separate material-flow evidence, partial-cost and reconciliation statuses.

**Cost model:**

- A node records its **total cost at its declared occurrence scope**, together with known subtotal, completeness, currency and source references. Keep a source production instance's full output cost distinct from the portion represented by a receiving occurrence.
- A material-to-product edge records **labour cost and manufacturing overhead** attributable to that production relationship. Keep the two components separately inspectable; their sum is processing cost. Material issue quantity/amount remains separately named flow evidence on or alongside the edge. Never relabel the existing material `amount` as labour/overhead.
- Every processing allocation requires source identity, receiving production/position, currency, cost scope and a declared allocation basis or explicit relationship allocation. Production-level labour/overhead totals must not be repeated on every incoming edge. Without a justified allocation, retain them as unallocated evidence and report incompleteness.
- For a purchased raw-material occurrence, its recorded attributable issue valuation supplies the cost basis; no manufacturing step is invented. An unresolved produced terminal's recorded valuation can be shown, but its internal cost breakdown remains unresolved.

**Computation:**

(1) For each accepted edge `(u, v)`, calculate the material quantity and amount received by `v` from the corresponding actual issue records. Attach the separately sourced labour and overhead allocations. Preserve original and projected source values and their references.

(2) After all incoming contributions to a receiver are accounted for, calculate:

```text
nodeTotalCost(v) = sum(accepted material issue amounts received by v)
                 + sum(labour allocations attributed to v's accepted incoming edges)
                 + sum(overhead allocations attributed to v's accepted incoming edges)
```

This is a complete total only when all required material and processing inputs and allocations are complete and have compatible scopes. Missing labour/overhead is **not zero**; zero must be supported explicitly. Retain a known subtotal separately and mark the total incomplete/unresolved when required inputs are absent.

(3) Upward propagation carries the attributable contribution identified by the **next receiver's issue records**, not the child's whole total. An explicitly linked source-share projection supplies a traceable cost breakdown for that flow. Keep any difference between that projected source cost and the recorded downstream issue valuation as a valuation reconciliation item. Do not add both as independent material contributions or silently substitute one for the other.

(4) Count each original issue/processing contribution once at its receiving scope. An ancestor's issue valuation may already incorporate its child's material and processing costs: do not add the child's total or descendants' amounts again. Node totals are summaries, not additional contributions to be summed across the tree.

(5) Preserve stable occurrences for distinct positions/source instances and all issue documents feeding the same root. Shared upstream breakdowns are projected into their explicitly attributable occurrences; underlying shared evidence must not inflate receiving totals. Do not copy the whole source batch or duplicate a processing allocation across paths.

(6) Keep manufacturing topology separate from document splitting and inventory bookkeeping. Store accepted edges in material-to-product direction; display may run root-to-leaves. Retain the top-down scope and upward exclusions alongside the final CSR for audit.

# Step 4: Resolve Outputs and Reconcile Evidence

**Inputs:** Accepted node/edge costs, original output records, output interpretation evidence, units/currency and completeness metadata.

**Outputs:** Output quantities/costs, separate material and processing subtotals, calculated node totals, valuation differences and traceable statuses.

**Computation:**

(1) Root output quantity comes from authoritative output records, not the sum of child quantities. With the current denormalised issue table, repeated `product_num` totals count once only after their instance-total interpretation is confirmed. Conflicts return `OUTPUT_QUANTITY_CONFLICT`; absence returns `MISSING_OUTPUT_QUANTITY`. Equal values do not establish semantics. Independent output receipts require explicit receipt identities and must not be deduplicated merely because their values agree.

(2) Apply the same interpretation checks to `product_total_cost`. Keep this recorded output valuation separate from the calculated node total and from `recordedMaterialAmount`. Do not derive missing labour/overhead by subtracting these values.

(3) Preserve `recordedMaterialAmount` as the non-overlapping root receiving-issue subtotal for compatibility. Expose root/node total cost and labour/overhead as separate fields. Retain existing material issue amount fields with their documented meaning when extending request/response contracts.

(4) Compare recorded output valuation with the calculated total only when both are complete and their cost scopes agree. Label material-only comparisons separately. Preserve existing discrepancy fields' documented semantics or explicitly version/update their callers and tests; do not silently change a material-only difference into a total-cost difference.

(5) Retain every accepted/excluded/unmatched source issue and processing allocation with reasons, graph identity, root scope, production relationships and known/unknown values. Report incomplete graph/import coverage, units, classification, source batches, processing allocations and returns explicitly. Never force a reconciliation to zero or invent balancing costs.

(6) Optionally report attributable consumption per resolved positive output unit. This derived metric does not change FGW weights, embeddings or objective.

# Step 5: Prepare Production-ID Trees for FGW

**Inputs:** Trees from the required two-pass construction, scope/cost/source completeness metadata, prepared 384-dimensional node embeddings and comparable IDs of the same product. The proposed independent workflow prepares twelve-field occurrence-context vectors after final reindexing; existing Java callers continue to supply vectors directly.

**Outputs:** Aligned CSR/embeddings, production-ID/source mappings, explicit eligibility and pairwise FGW inputs.

**Computation:**

(1) Assign stable occurrence indices and build CSR, retaining root identity and all actual branches. The surrounding workflow then performs [contextual embedding preparation](<Embedding_Implementation.md#occurrence-context-embedding-preparation>), including the current material's code/name/specification/supplier/category/unit cost and direct upstream/downstream codes/names/specifications. Align one 384-dimensional vector with each final occurrence and preserve model/template/context provenance. Report missing vectors; tree construction itself performs no model inference. Supplier, unit-cost or neighbour differences require separate context inputs even for equal material codes; equal vectors do not merge occurrences.

(2) Map the scoped production ID to native `Graph.instanceId` and receiving product to `Graph.productId`. Populate `NodeSource.productionOrderId` with the confirmed receiving production ID for that occurrence's step. Preserve issue IDs and decimal quantity/amount text. Retain top-down scope, exclusions, upstream-source relationships, node cost completeness and edge processing allocations alongside native input when its current fields cannot represent them.

(3) Leave legacy `NodeSource.fifoAllocationIds` empty. Preserve that field for interface compatibility; do not fabricate FIFO references or repurpose it for production IDs.

(4) Compare different IDs of the same product at the same declared topology scope. Complete direct-consumption trees may be compared at direct-consumption scope. Accept full-lineage comparisons only when required upstream links are resolved. Report incompatible/incomplete inputs instead of claiming a complete comparison. Keep topology, lineage and cost completeness separate and preserve all of them alongside native results. A topology comparison may remain explicitly qualified when processing costs are missing; it must not claim complete total-cost restoration. The solver does not validate these semantics.

(5) Preserve standard balanced FGW mathematics. Quantity and amount metadata are not direct distance terms. With fixed supplied vectors, metadata-only quantity/price changes do not affect the objective. The proposed context profile includes unit cost and can therefore affect the attribute term indirectly when vectors are regenerated; textual distances are not exact financial differences. Preserve decimal financial evidence separately and retain all fixed numerical expectations.

# Tree acceptance requirements

Run the [production-tree tests](FGW_Build_Production_Trees_Test_Case.md): complete seven-node P1 restoration, the unchanged grouping and partial-share cost fixtures, all validation requirements and T01–T14. Inputs must be passed explicitly through in-memory providers with no database reads. The linked document owns expected values and reporting requirements; its migration does not relax acceptance.

# Deliverables for Subsequent Implementation

- Verified production-ID mapping, instance selection and explicit preceding-stage CSR integration.
- Per-root top-down node/edge/path/terminal scope and bottom-up accepted/excluded receiving evidence; one actual occurrence tree per production ID.
- Exact attributable issue quantities/material amounts, node total/partial costs, separately sourced edge labour/overhead, allocation provenance and valuation reconciliation; explicit lineage where available.
- FGW adapter preserving production IDs, source mappings and unchanged mathematics.
- Updated Java service, tree builder, DTOs, affected callers/API documentation and automated two-pass/grouping/cost/evidence tests with a requirement-by-requirement PASS/FAIL report.
- Record any unavailable schema, source-batch or cost-allocation evidence precisely. Do not claim full restoration solely because grouping, a build or the FGW solver succeeds.

The [workflow overview](<FGW_Algorithm_Design.md>), [FGW implementation specification](<FGW_Implementation_Five_Steps.md>) and [fixed numerical fixture](<FGW_Test_Case.md>) cover separately scoped FGW work. This tree update preserves its mathematics and fixtures; it does not authorise GPU implementation or optimisation. Rewriting this document does not execute subsequent code stages or validate their behaviour.


