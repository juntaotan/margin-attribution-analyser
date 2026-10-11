# Production-tree acceptance tests

Implementation: [production-tree restoration](FGW_Build_Production_Trees.md). This document owns the tree fixtures and acceptance requirements migrated from that specification. All original fixed values and T01–T14 cases remain unchanged below. Numerical FGW regression remains in [its existing test document](FGW_Test_Case.md).

## Complete primary instance: control-enclosure P1

Use the actual-period records in the [shared fixture](Analysis_Fixture.md), the preceding actual CSR built from those records, selected root P1, `FULL_LINEAGE`, complete graph coverage, explicit source identities/shares, output receipts and processing sources/allocations. Supply everything through deterministic in-memory providers; do not access a database or bypass traversal by supplying a completed tree.

```text
A (P1)
├── S1 (P1-S1; sourceShare=1)
│   ├── R1
│   └── R2
└── S2 (P1-S2; sourceShare=1)
    ├── R3
    └── R5
```

1. Top-down traversal records the seven permitted material positions, six relationships, four root-to-terminal paths and terminal materials R1/R2/R3/R5. Retain original CSR node/edge identities and coverage.
2. Terminal lookups return original issue records. Bottom-up attribution follows the explicit receiving/source batches and permitted paths, finalising S1/S2 before A. Complete-instance lookups resolve all relevant rows from the passed inputs.
3. Assert seven distinct occurrences, six edges, one A root and no synthetic COGS occurrence. Decode final CSR and verify its identities/relationships against the scope, original issues and source shares; do not impose a new output index order where the implementation contract does not specify one.
4. Assert S1 material subtotal 140, labour 6, overhead 4 and complete total 150; S2 material subtotal 90, labour 6, overhead 4 and complete total 100. Root incoming valuation is 250, labour 12, overhead 8 and complete total 270. Never add descendant totals again.
5. Confirm ten output units, complete cost per output 27, complete-total output discrepancy zero and preserved material-only output discrepancy 20. Assert topology, lineage and cost completeness separately, exact decimal response representation and allocation/output provenance. No model generation or FGW execution is required to establish this tree result.

Add a scope-exclusion variant: terminal lookup for R1 also returns an identified 3 kg/18 NZD issue to S1 of an unrelated supplying batch `Q-S1`, with no declared relationship to P1. Retain it as original candidate/exclusion evidence; it contributes nothing to the P1 tree or costs. Provider-side pre-filtering that prevents the algorithm seeing this candidate does not test exclusion.

## Boundary coverage and reporting

Run the primary instance plus the original fixed grouping/share fixtures and all T01–T14 variants below. Cover separate same-product production IDs; prohibited edges despite permitted nodes; shared material positions; split/duplicate/conflicting rows; full-instance retrieval across dates; missing supplying ID/share; 0.2 source projection; missing versus explicit zero processing; over-allocation; incompatible unit/currency/scope; returns/cycles/coverage; and consistent graph direction/reindexing.

Report actual nodes/edges, accepted/excluded/unmatched original identities, projected sources/shares, exact costs, completeness/statuses and PASS/FAIL for the primary case and each required variant. Prove that the exercised construction path does not access a database. Use the existing Java 21 Maven test command prescribed in AGENTS.md. Adding this document does not execute those tests.

# Validation Requirements for Subsequent Implementation

Implement and run automated tests, reporting PASS/FAIL for:

1. Direct `order_no` to `productionId` mapping, missing IDs and scoped identity.
2. Several issue documents/dates for one ID yielding one tree; two IDs of the same product yielding two trees.
3. Multiple products under one ID yielding `AMBIGUOUS_PRODUCTION_PRODUCT`.
4. Full-instance retrieval across reporting boundaries and incomplete dataset coverage.
5. Actual issue quantity/amount conservation, decimal precision and each row included once.
6. Repeated output totals counted once, conflicting totals reported and independent receipt identities respected.
7. Same-position material aggregation preserving references; distinct positions/source instances remaining separate.
8. Actual unexpected materials retained despite unreliable BOM.
9. Explicit multilevel links and partial upstream shares without duplicate consumption or double-counted amounts; missing links reported without chronological/FIFO inference.
10. Missing inputs/units, output-cost discrepancies, returns, cycles and incomplete relationships.
11. CSR, embeddings, root, production IDs and source references aligned after reindexing; legacy FIFO references empty.
12. Same-product eligibility and matching topology scope. Keep the fixed FGW numerical fixture and expected values unchanged.
13. Required first-pass input and traversal: direction/transposition, multiple roots, all intermediate/terminal nodes, direct and deep branches, malformed CSR, cycles and missing/cross-period scope coverage. Prove that the final output CSR does not replace the input pass.
14. Terminal material shared by an allowed and an excluded receiver: preserve all candidate issue identities but attribute only the allowed receiver's actual quantity/amount. Excluded records must have explicit reasons.
15. Both endpoint codes present in `S` but their connection absent from `E`, or their receiving/source batch incompatible with the selected root: reject that contribution despite node membership.
16. Bottom-up dependency ordering, converging/shared paths, repeated queries and repeated document references without loss or duplicate attribution. Each selected production ID retains its own scope and evidence.
17. Node total cost versus separately named material flow and edge labour/overhead; explicit zero costs versus missing inputs, partial totals, allocation provenance and incompatible currency/cost scope.
18. Production-level processing costs split across several incoming edges by explicit allocation evidence and counted once. Missing/unsupported allocation remains unallocated; duplicating the full total on each edge must fail validation.
19. Intermediate cost already embedded in downstream issue valuation: no repeated addition of the intermediate's total or its ancestors' processing costs. Report source-projection/downstream valuation differences separately.
20. Execute the two-pass cost fixture below and the unchanged original grouping fixture through the application construction path using explicitly passed in-memory inputs. Follow the final chapter for database-free test execution, output-contract assertions and the detailed acceptance cases.

# Fixed Production-ID Grouping Test

Treat `order_no` as a confirmed receiving production ID and `product_num` as a repeated instance total. Issue quantities use one unit per material; amounts share one currency and material-cost scope. Supply an actual top-down graph with root A and material children B/C for each selected instance. Preserve the following original material grouping expectations; they do not supply or prove processing costs. Assert complete root total costs only with explicit zero processing allocations, otherwise assert missing processing evidence and partial totals.

```text
productionId  productId  issueLineId  movementNo  outputQty  material  issueQty  amount
P1            A          L1           M1          20         B         100       200
P1            A          L2           M2          20         B          20        60
P1            A          L3           M3          20         C           5        40
P2            A          L4           M4          10         B          30        90
P2            A          L5           M5          10         C           2        20
```

Expected results:

```text
Exactly two trees, both for product A:

P1 root: output quantity 20, recorded material amount 300
  B: quantity 120, amount 260, issue references [L1, L2]
  C: quantity   5, amount  40, issue references [L3]

P2 root: output quantity 10, recorded material amount 110
  B: quantity  30, amount  90, issue references [L4]
  C: quantity   2, amount  20, issue references [L5]

Recorded issue conservation:
  B quantity: 150 = 120 + 30
  C quantity:   7 =   5 +  2
  Amount:     410 = 300 + 110

No receipt-layer allocation or FIFO calculation occurs.
```

The root material amount is a labelled child subtotal, not another financial contribution. Do not sum it with the children again. Changing input order or splitting L1 into two explicitly distinct source rows with the same combined quantity/amount must preserve material topology and totals, while retaining the new references. FGW must preserve P1/P2 instance IDs. Identical topology and material embeddings give identical mathematical FGW inputs despite different quantity/amount metadata.

# Fixed Two-Pass Cost and Exclusion Test

Use a verified actual scope for root `(fixture, P_A, A)`:

```text
Top-down adjacency: A -> S -> R
S = {A, S, R}
Allowed upward relationships: R -> S, S -> A
Terminal set: {R}
Explicit source link: A's S occurrence consumes 0.2 of homogeneous P_S output.
Source production P_S produces S; receiving production P_A produces A.
```

Use one declared currency and compatible valuation scopes. Issue identities and output quantities are explicit:

```text
issueLineId  material  receiverProduct  receiverProduction  quantity  materialAmount
L_RS         R         S                P_S                 10        100
L_RB         R         B                P_B                 99        990
L_SA         S         A                P_A                  2         30
L_SC         S         C                P_C                  8         96

P_S output quantity: 10; recorded output cost: 120
P_A output quantity:  2; recorded output cost:  40

Full-source edge allocations, each with a distinct processing source reference:
R -> S at P_S: labour 12, overhead 8
S -> A at P_A: labour  4, overhead 6
```

Expected results:

- The terminal query retains `L_RS` and `L_RB` as candidate evidence. Reject `L_RB` because B is outside `S`. At the next level, reject `L_SC` because C is outside `S` and P_C is not P_A.
- Full source P_S cost is `100 + 12 + 8 = 120`. Preserve it as source-instance evidence, not a full receiving-occurrence contribution.
- The S occurrence within P_A represents only the explicit 0.2 source share. Its underlying R quantity is 2 and R material valuation is 20; its projected edge labour is 2.4 and overhead is 1.6. Its projected source-cost breakdown is `20 + 2.4 + 1.6 = 24`.
- The actual S quantity received by P_A is 2 and its recorded issue valuation is 30, from `L_SA`. Preserve that receiving valuation separately from the projected source total 24; their valuation difference is 6.
- The root material subtotal is 30. Its incoming edge labour is 4 and overhead is 6. Root calculated total cost is `30 + 4 + 6 = 40`. Neither 120 nor 24 nor the projected R amount 20 is added again to this total. Recorded root output cost reconciles to 40.
- The two excluded issue amounts 990 and 96 contribute zero to P_A's totals while remaining traceable as exclusions. Original source issue/allocation values remain available separately from projected amounts.
- If the labour or overhead allocation for S -> A is absent, the root total is incomplete rather than assumed to be 40. Removing an allocation does not change the known material amount 30. An explicit zero allocation is distinguishable from an absent allocation.
- Independently permuting graph/node/issue order preserves accepted identities, topology and amounts after consistent reindexing. Repeated visits to L_RS must not multiply its contribution.

Also test a graph where both endpoint codes are permitted but a candidate cross-edge is absent from `E`, and a second production ID of A receiving S. Neither node membership nor equal product codes may cause those records to enter P_A.


# Required Tests with Passed Inputs and No Database Reads

This chapter records the user's confirmed test approach and is authoritative for the new two-pass acceptance tests: **supply all test data explicitly as inputs; do not read it from a database**. This changes the validation approach, not the production requirement to retrieve actual issue evidence through the application's data-access boundary.

## Test Execution Contract

- Supply selected roots/production IDs, source namespace, actual CSR and direction/coverage metadata, issue rows, output records/interpretations, units/currency, source-production links/shares and processing allocations as fixture parameters.
- If the existing entry point uses loaders or providers, pass deterministic in-memory implementations backed by the supplied fixture. Terminal-material lookups and full-instance lookups must resolve from these inputs. Candidate lookups must include other receivers so that exclusion is exercised, rather than returning only pre-filtered accepted rows.
- Exercise the actual scope traversal, upward attribution, cost aggregation and response construction used by the application. Do not implement a separate test-only algorithm or validate only an isolated arithmetic helper. Where orchestration requires dependencies, inject the in-memory input boundary without changing production source semantics.
- No database connection, JPA query, H2 fixture, Testcontainers, Flyway execution or live warehouse access is required for these new tests. A test must fail if its construction path attempts database access. Existing database-oriented tests may remain, but they do not replace or impose a database dependency on these acceptance cases.
- Validate returned node/edge values, accepted/excluded/unmatched records, original and projected source references, identity/scope and completeness statuses. Test response mapping and exact decimal serialisation through passed inputs; an HTTP server or database-backed endpoint test is not required.
- Use `BigDecimal` or the existing exact-decimal representation. Preserve fixed expectations and report each case as PASS/FAIL. Run the prescribed Java tests under Java 21 using the project's documented environment; this documentation change itself does not execute the proposed tests.

## Acceptance Cases

| ID | Passed inputs | Required assertions |
| --- | --- | --- |
| T01 — Complete multilevel attribution | Pass the complete fixed two-pass fixture above: `A -> S -> R`, P_A/P_S identities, source share 0.2, all four issue rows and both processing allocations. | Record scope nodes A/S/R, edges A->S/S->R and terminal R. Exclude L_RB and L_SC with reasons; retain their original references. Full source cost is 120; the S occurrence's projected source cost is 24, with R quantity 2, material amount 20, labour 2.4 and overhead 1.6. Downstream S issue quantity is 2 and valuation is 30; valuation difference is 6. Root material amount is 30, labour 4, overhead 6 and total 40. Do not add 120, 24, 20, 990 or 96 again. |
| T02 — Invalid connection within the node set | Supply two nodes both reachable from the root, but omit the candidate connection between them from the permitted edge/path set. Include an issue row for that connection. | Reject that contribution despite both endpoint codes being in the node set; preserve its source reference and explicit exclusion reason. No amount or processing cost for the rejected connection enters the root. |
| T03 — Same product, different production IDs | Add S issues to A/P_A2 as well as A/P_A; select P_A alone and then both IDs. Provide explicit compatible source links for accepted instances. | P_A2 records never enter P_A. Selecting both IDs yields separate trees/scopes and evidence, even though the product code is A. Preserve all selection references and the confirmed receiving-ID mapping. |
| T04 — Direct and deep branches | Extend T01 with direct purchased material T->A, issue amount 5 and explicitly zero labour/overhead on that edge. | Top-down traversal discovers both R and T as terminals and retains both paths. Complete both branches before finalising A. Root total is 45; reversing branch processing order does not change it. |
| T05 — Shared terminal and converging branches | Pass `A -> S1 -> R` and `A -> S2 -> R`, with distinct production/position identities and explicit source links/shares. Let S1->A and S2->A issue amounts be 40 and 60; declare complete zero processing allocations. | Preserve separate R occurrences and branch references where their receiving positions differ. Root material amount and total are 100. Reaching shared source evidence along two paths does not multiply a contribution or merge distinct occurrences. |
| T06 — Duplicate retrieval and genuine row splitting | Return the same `(id,date)` through repeated in-memory lookups/visits. Separately split one original issue into two distinct row identities with equal combined quantity/amount; include equal document references. Also supply conflicting values for one composite identity. | Repeated retrieval counts once per legitimate receiving contribution. Distinct split rows both survive and preserve their references; totals/topology remain equivalent. Document equality is not deduplication. Conflicting records for one identity produce explicit rejection/conflict evidence. |
| T07 — Processing allocation across incoming edges | Two root issues have amounts 60 and 40. Supply receiving-production labour total 10 and overhead total 5, with explicit edge allocations 6/4 and 3/2 and allocation/source identities. | Root material amount is 100, labour is 10, overhead is 5 and total is 115. Preserve edge allocations and their provenance. Duplicating the full 10/5 on both edges, reusing one allocation as two independent costs, or supplying inconsistent allocation totals must fail validation or mark the allocation unresolved. Missing allocation basis remains unallocated, not equally divided by assumption. |
| T08 — Missing processing cost versus explicit zero | For an otherwise complete material issue amount 30, run fixtures with labour/overhead absent, one component absent, and both components explicitly zero with source evidence. | Missing components produce an incomplete/unresolved total and a labelled known subtotal, never assumed zero. Explicit zero allocations yield complete total 30 when all other required evidence is complete. Return material, processing and completeness fields separately. |
| T09 — Missing supplying batch or share | Remove the supplying production ID or explicit 0.2 share from T01. Retain the downstream S->A issue and root processing allocations. | Keep known downstream material amount 30 and its original evidence. Mark source lineage/projected cost breakdown unresolved; do not choose a batch by code/date/FIFO or infer a share. Distinguish known receiving cost from incomplete multilevel lineage; do not claim complete restoration. |
| T10 — Cross-period instance and graph coverage | Pass selected P_A records both inside and outside the selection period. In a second variant, supply a graph whose declared coverage omits a relevant relationship. | Selection dates do not truncate the supplied instance's issues. Incomplete graph coverage yields explicit incomplete-scope/unmatched evidence; no silent removal or complete-tree claim. Missing imported coverage declarations remain explicit. |
| T11 — Decimal precision and incompatible units/currency | Supply compatible contributions 0.1 and 0.2, and separately 9007199254740993.01 and 0.02. Include incompatible-unit/currency/cost-scope variants and a missing amount. | Exact totals are 0.3 and 9007199254740993.03, with exact decimal response text. Do not sum incompatible quantities/currencies or force valuation reconciliation across incompatible scopes. Missing amounts remain missing; partial known subtotals do not imply complete totals. |
| T12 — CSR direction, malformed structure and reordering | Supply equivalent material-to-product and product-to-material CSR fixtures with explicit direction, then consistently permute nodes, issues and edges. Supply separate malformed-offset, invalid-successor, missing-root, cycle and incomplete-relationship fixtures. | Equivalent valid directions/reindexings produce the same scope, accepted identities and financial results. Validate direction/transposition and graph identity. Invalid structures return explicit errors/statuses rather than partial traversal presented as complete. Prove that omitting the required preceding-stage graph cannot be hidden by generating a final CSR after grouping. |
| T13 — Existing grouping/output/FGW compatibility | Pass the unchanged P1/P2 fixture, repeated output totals, conflicting totals, distinct equal-valued output receipts, scoped identities and existing FGW inputs. | Preserve P1 material amount 300, P2 amount 110, B quantity 150, C quantity 7 and amount conservation 410. Count repeated verified totals once; keep distinct receipts separate. Missing/ambiguous IDs and conflicting output semantics remain explicit. Existing field meanings, same-product/scope eligibility, CSR/embedding/source alignment and empty FIFO references remain correct. Preserve FGW mathematics and fixed expected results; amount changes alone do not change its objective. |
| T14 — Incomplete inputs and returns | Pass missing material/unit/classification, unknown intermediate terminals, self-consumption, explicit reversal references and return rows without a declared netting rule. | Preserve original evidence and explicit incompleteness/boundary/return statuses. Do not treat unknown intermediates as restored raw leaves, invent a step, replace missing quantities with zero, or net returns without the required rule. |

## Required Test Deliverables

Implement automated tests for T01-T14, including meaningful parameterised variants where specified. Every fixture must enter through passed inputs or the deterministic in-memory input boundary. Preserve the original fixed grouping and FGW expectations; do not change them to fit the implementation.

Produce a PASS/FAIL checklist identifying each case, actual values/statuses and the test command executed. Clearly distinguish tests written, tests run and unavailable/unverified checks. Include evidence that the new acceptance tests do not access a database. A successful build, pre-filtered input or the existing database-backed suite alone does not establish acceptance of this algorithm.
