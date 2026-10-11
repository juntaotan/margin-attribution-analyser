# FGW algorithm design

Parent: [Main analysis design](Main_Analysis_Design.md). This document owns production-instance comparison boundaries and result interpretation. The parent owns period discovery, candidate/all-pairs selection, contextual embedding generation and CPU/GPU policy. Requirements describe intended behaviour; implementation and device correctness require actual validation evidence.

## Specifications and reading scope

| Stage | Authoritative child | Contract |
| --- | --- | --- |
| 1. Restore actual occurrence trees | [FGW_Build_Production_Trees.md](FGW_Build_Production_Trees.md) | Preceding actual CSR and selected roots/coverage; recorded issues, outputs, explicit source links/shares and processing allocations → separate production-ID trees, scope paths, accepted/excluded evidence, decimal costs and completeness. |
| 2. Compare eligible trees | [FGW_Implementation_Five_Steps.md](FGW_Implementation_Five_Steps.md) | Eligible same-product/scope trees, CSR, aligned finite 384-dimensional embeddings and numerical configuration → correspondence, separate objective terms, applied-update count, fresh gap and stopping status. |
| 3. Validate numerical behaviour | [FGW_Test_Case.md](FGW_Test_Case.md) | Fixed synthetic inputs and prescribed intermediate/final values → automated regression, independent CPU/POT comparison and PASS/FAIL report. |

Read only the children relevant to the changed stage, including their required validation. Tree changes require tree acceptance tests; solver changes require numerical regression and applicable execution/device tests. An integrated change must satisfy every affected stage. Complete numerical correctness before GPU performance optimisation. Fixed fixture labels “Normal Production” and “Actual Production” do not define business candidate selection.

## Stage invariants

- `productionId = inventory_usage.order_no`; retain namespace, product, occurrence, issue and explicit upstream-source identities. Separate production IDs and manufacturing positions remain separate, even when material codes agree.
- Tree preparation traverses the preceding actual CSR top-down, then attributes terminal issues bottom-up through permitted relationships and receiving/source instances. Final output CSR cannot replace the input pass. Load whole instances across selection dates.
- Use recorded actual issues, exact decimal financial values and explicit source shares/processing allocations. No FIFO, inferred batches, theoretical BOM substitution or fabricated missing inputs. Legacy `fifoAllocationIds` stays empty.
- Compare different instances of the same product, namespace and topology scope. Preserve separate topology, lineage and cost completeness, rejection evidence and missing-input statuses. Missing total-cost evidence qualifies otherwise eligible results independently of the mathematical objective.
- Preserve the standard balanced FGW objective, fixed weights, exact linear OT, line search, tolerances and fixtures defined by the implementation child. Add no monetary penalties, alternative transport formulation or automatic anomaly threshold.
- Join correspondence to occurrences and branches through retained source mappings; a pairwise score is not proof of a production cause.

## Embedding boundary

After final tree reindexing and before pair execution, the parent workflow prepares [twelve-field contextual embeddings](Embedding_Implementation.md#occurrence-context-embedding-preparation). That section owns fields, canonicalisation, supplier/unit-cost evidence, provenance, cache/batching, missing inputs and model/runtime validation. Reuse prepared vectors across all selected pairs; inference policy is independent of FGW execution policy.

The solver consumes one finite 384-dimensional vector per occurrence, including roots. Tree construction and FGW perform no model inference. Existing Java comparison endpoints remain supplied-vector interfaces; proposed generation does not change their contracts or fixed numerical inputs. Contextual unit cost/neighbours can affect regenerated attribute vectors; fixed-vector quantity/amount metadata does not directly alter the objective. Attribute cost is not a currency difference.

## Comparison result interpretation

`FgwEngine.Result` describes one left/right production-tree comparison. Its central output is a node correspondence matrix and the objective costs under that correspondence, not a monetary variance or a ready-made anomaly diagnosis.

| Field | Interpretation |
| --- | --- |
| `rows`, `columns` | Left and right tree node counts, including roots. |
| `left`, `right` | Product/instance/root identity and ordered node source mappings. Each node includes occurrence, material, receiving production, issue IDs and decimal quantity/amount text. |
| `p`, `q` | Fixed node weights: `1/rows` and `1/columns`, summing to one on each side. |
| `starts`, `bestStart` | Results for each initial transport matrix and the selected lowest-cost non-numerical-failure start, when one exists. Check its status; selection does not imply convergence. |
| `starts[k].correspondence` | Flattened row-major transport matrix `P`; entry `(i,j)` is at `i * columns + j`. |
| `starts[k].costs.material` | Transport-weighted squared distance between the supplied node embeddings; proposed contextual inputs include all twelve fields. This is an attribute objective term, not a currency amount. |
| `starts[k].costs.structure` | Transport-weighted squared differences of within-tree shortest-path distances. |
| `starts[k].costs.fgw` | `(1-alpha) * material + alpha * structure`; default `alpha=0.8`. |
| `starts[k].iterations`, `finalGap`, `status`, `diagnostic` | Applied update count, final Frank–Wolfe gap and numerical/stopping evidence. |
| `totalMilliseconds`, `execution` | Runtime and implementation identifier; the current Java result uses `JAVA_SERIAL_EXACT_SIMPLEX`. |

`P[i,j]` is correspondence mass, not a consumed quantity or probability that a material caused an anomaly. Its row sums equal `p[i]`, column sums equal `q[j]`, and total mass equals one. A row can distribute its mass over several right nodes. For a row-level share, `P[i,j]/p[i]` gives the portion of that left node's weight assigned to right node `j`; it still is not an anomaly probability or a physical allocation. All tree nodes participate, including roots and intermediate occurrences. Use the source mappings to recover which materials and production occurrences correspond.

Lower FGW cost means a closer match under the configured attribute/topology objective. It is not a calibrated percentage similarity, a currency amount or proof of normality. The nonconvex calculation and its stopping status do not guarantee a global optimum. Quantity/amount metadata does not directly enter the present solver objective. In the proposed twelve-field generation stage, a changed unit cost can indirectly affect the attribute term through a changed embedding; fixed supplied vectors remain unaffected by metadata-only price changes.

Only `FW_GAP_REACHED` yields `converged=true`. `COST_STALLED`, `MAX_ITERATIONS` and `NUMERICAL_FAILURE` do not. With `captureTrace=true`, the result additionally contains material and structural distance matrices and per-iteration matrices, objective, marginals, line search and linear-OT diagnostics. Without tracing these optional arrays/lists remain empty; do not promise detailed OT certificates as default output.

The HTTP `ProductionComparisonResponse` wraps this in `result`, together with `statuses`, `missingVectors`, `embeddingVersions`, inspected `trees` and nullable `converged`. Ineligible or incomplete inputs return evidence/statuses with `result=null`; an executed solver can still return a failure or nonconverged result.

## Deliverables by affected stage

1. Tree implementation, occurrence/CSR/source mappings and automated grouping/scope/exclusion/cost/evidence acceptance tests, with requirement-by-requirement PASS/FAIL results.
2. Standard FGW comparison and source-mapped output, fixed numerical regression, independent CPU/POT evidence and applicable device results.
3. For integration tasks, contextual embedding preparation/provenance and the parent's mapping/cache/missing-input/model/runtime checks, plus truthful stage execution reports.

Do not claim unexecuted tests, generation, acceleration binding or production readiness from a documentation change. The [Java API reference](<Production Tree Java API.md>) records current HTTP contracts and validation links.
