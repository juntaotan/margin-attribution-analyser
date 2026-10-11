# Abnormal branch acceptance tests

Implementation: [branch discovery](Abnormal_Branches_Implementation.md). Supply two explicit tree-shaped graphs with source mappings, or obtain them through the CSR fixture's in-memory record construction. Exercise preparation, frontier selection and original-path reconstruction together. No database is required.

## Fixed normal and cost-change graphs

Use the [CSR tree](CSR_Test_Case.md#fixed-tree-and-source-records): A→B→D and A→C→E in product-to-material display direction. All explicit node cost inputs are comparable. Baseline costs: A=180, B=100, C=80, D=100, E=80. Actual costs: A=200, B=120, C=80, D=120, E=80. A costs here are explicit branch-test inputs, not a claim that the current CSR loader calculates them from its child sums. Keep synthetic COGS outside this minimal frontier fixture.

```text
nodes      = [A,B,C,D,E]
offset     = [0,0,1,2,3,4]
successors = [0,0,1,2]
leafThreshold = 10
stopThreshold = 15
```

Only D is a selected seed. B is selected with absolute difference 20 and stops propagation. Assert exactly the decoded path D→B, original node positions `[3,1]`, original edge index 2 and difference 20. The normal E→C branch has no returned path; A is not selected through blocked B. The algorithm uses supplied differences, not accumulated child differences.

## Structure and cost changing together

Replace the abnormal subtree D→B with actual D→X→B, leaving A→C→E unchanged. Baseline remains the fixed tree above. Actual costs are A=200, B=120, C=80, D=120, E=80, X=120. X is an actual-only intermediate, so its cost is noncomparable rather than fabricated as a zero-baseline difference.

```text
actual nodes      = [A,B,C,D,E,X]
actual offset     = [0,0,1,2,3,4,5]
actual successors = [0,0,5,2,1]
```

The common leaf D still seeds by its cost difference. X propagates without being selected as comparable; B reaches the stop threshold. Assert exactly D→X→B, positions `[3,5,1]`, original edge indices `[2,4]` and boundary difference 20. Assert no path on the unchanged E→C branch and retain actual-only X evidence. This demonstrates changed structure on a cost-seeded branch; it does not establish general structural anomaly detection.

## Cases and limits

| ID | Variation | Required assertions |
| --- | --- | --- |
| B01 | Identical topology and costs | No selected seeds/boundaries/paths. |
| B02 | Fixed cost-change fixture | Exactly D→B; normal branch absent. |
| B03 | Insert X with the cost change above | Exactly D→X→B, using actual graph edges and source identity. |
| B04 | Common leaf D changes cost and moves from B to C; other leaf costs unchanged | Follow D's actual receiving connection. With C's explicit difference above the stop threshold, return D→C; no invented old D→B edge. This variant changes both connections and receiver costs, so record the affected scope rather than claim only one unchanged ancestor. |
| B05 | Difference equals a threshold, just below it, and a negative difference with the same magnitude | Inclusive absolute-value thresholds; six-decimal exact arithmetic. |
| B06 | Changed leaf, intermediate below stop threshold, comparable ancestor above it | Propagate through the intermediate and select the qualifying ancestor using its supplied difference. |
| B07 | Selected child and shared ancestor | Selected child blocks further propagation; do not select a shared ancestor prematurely. |
| B08 | Missing costs or actual-only/replaced leaf | Explicit noncomparable/missing evidence; no invented zero-baseline cost. No cost seed is guaranteed for an unmatched leaf. |
| B09 | Pure structure change with all common costs unchanged | Document current limitation: no qualifying cost seed, hence no guaranteed structural path. Do not require new structural detection in this task or label no path as proof of normal topology. |
| B10 | Invalid CSR/DAG, threshold precision/range and consistently reindexed graphs | Invalid inputs fail at the validating boundary; equivalent valid reindexings preserve decoded paths and financial differences. |

For B04 provide the complete variant graph/cost arrays in the automated fixture and report them; do not infer its costs during pruning. Missing-value and pure-structure cases are limit/error checks, not alternative numerical definitions.

## Execution and report

Implement automated tests using real Java preparation/frontier/reconciliation. Run the prescribed Java 21 Maven test command. Report B01–B10, actual paths, boundary differences, graph positions/edge identities and limitations. If accelerated execution is changed, additionally run its prescribed device/parity checks. No runtime pass is established by this document.
