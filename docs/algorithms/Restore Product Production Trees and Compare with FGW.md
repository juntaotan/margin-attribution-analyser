# Restore Product Production Trees and Compare with FGW

# Objective

Implement production-branch analysis by traversing the preceding actual CSR top-down and attributing recorded issues bottom-up into one occurrence tree per production ID, then comparing different production instances of the same product with standard balanced Fused Gromov-Wasserstein (FGW). The user-confirmed mapping is `productionId = inventory_usage.order_no`; do not perform FIFO allocation or infer missing batches.

Follow the three instruction files below in order. Treat them as execution instructions for the implementation agent.

# File 1: Build Product Production Trees by Production ID

Read [Build Product Production Trees by Production ID](<Build Product Production Trees by Production ID>). Its revised two-pass construction supersedes the earlier issue-grouping-first workflow.

**Inputs:** Selected production IDs or existing abnormal-branch results, preceding-stage verified actual CSR with explicit roots and coverage, recorded material issues/output metadata, explicit upstream links/shares, and referenced processing-cost allocations.

**Outputs:** One actual occurrence tree per production ID, per-root permitted nodes/edges/paths/terminals, accepted/excluded issue and allocation evidence, exact material flows, node totals/known subtotals, edge labour/overhead, final CSR and separate topology/lineage/cost completeness. Full multilevel lineage requires explicit upstream production links.

**Computation:**

(1) Select requested production IDs directly, or reuse abnormal-branch discovery to locate affected products and candidate production instances.

(2) Validate the preceding actual CSR and its selected roots, derive product-to-material adjacency from its declared direction, and traverse every direct/deep branch to record scope and terminals. Keep different production IDs and root scopes separate even when product codes agree.

(3) Query every terminal material's available issues, retaining candidate/exclusion evidence, then trace material-to-product relationships upwards. Attribute only permitted edges and receiving/source batches. Retrieve whole receiving/upstream instances across selection dates. Expand only explicitly linked source shares; retain unresolved intermediates as boundary nodes.

(4) Finalise receivers in dependency order using exact decimal material flows plus independently sourced edge labour/overhead. Keep missing inputs and unallocated costs explicit, preserve complete/known node totals and reconcile source projections separately from downstream issue valuations. Resolve confirmed output totals once and preserve the legacy material-only discrepancy alongside the new total-cost comparison. Never add descendant totals again.

(5) Preserve one tree per production ID and prepare the existing FGW interface with aligned embeddings and source mappings. Leave legacy FIFO references empty. Compare only matching topology scopes with explicit eligibility; qualify missing total-cost evidence independently of the unchanged mathematical objective.

Complete the grouping, tree and evidence checks in File 1 before supplying accepted inputs to File 2. A documentation-only revision does not execute these subsequent implementation stages.

# File 2: Implement Standard FGW in Five Steps

Read [Implement Standard FGW in Five Steps](<FGW Implementation - Implement Standard FGW in Five Steps.md>).

**Inputs:** Two eligible actual product trees from File 1 at the same topology scope, CSR topology, and caller-supplied 384-dimensional material content embeddings aligned with tree node indices.

**Outputs:** Correspondence matrix, material cost, structure cost, FGW cost, iteration count, and convergence status.

**Computation:**

(1) Compare different production IDs of the same product. Preserve production-instance identity and completeness metadata when selecting tree pairs.

(2) Execute the five prescribed FGW steps: distance matrices, fixed node weights, initial cost, Frank–Wolfe optimization, and convergence verification.

(3) Map the correspondence back to product-tree occurrences and branches. Include recorded quantities, amounts, production IDs and issue/source references in the comparison output.

(4) Apply the objective, distance definitions, weights, and numerical settings specified in File 2. Restored quantity/amount metadata does not implicitly change that objective. Do not introduce additional attribute penalties, a different transport formulation, or an automatic anomaly threshold without an explicit specification.

# File 3: Validate the FGW Numerical Implementation

Read [FGW Numerical Test Case](<FGW Implementation - Test Case.md>).

**Inputs:** The fixed graphs, embeddings, configuration, and expected intermediate values in File 3.

**Outputs:** Automated numerical regression and a step-by-step PASS/FAIL report.

**Computation:**

(1) Validate every prescribed intermediate result and the final result.

(2) Investigate discrepancies without changing the expected values.

(3) Compare against an independent CPU reference and Python Optimal Transport (POT).

(4) Complete numerical correctness validation before GPU performance optimization.

The labels "Normal Production" and "Actual Production" in File 3 describe its fixed mathematical fixture. Production tree selection follows the actual-tree comparison workflow above.

# Execution Requirements

- Reuse the existing abnormal-branch identification and graph-tracing capabilities.
- Keep File 1 responsible for production-ID grouping, recorded consumption trees and source evidence.
- Keep File 2 responsible for the standard FGW pairwise calculation.
- Keep File 3 responsible for the fixed FGW numerical regression.
- Preserve production-ID, product, occurrence, issue-line and explicit upstream-source identities across all stages. Retain native legacy FIFO fields for compatibility, with empty values for this workflow.
- Do not merge separate product instances or separate branches solely because they share a material number.
- Do not replace actual issue quantities with theoretical BOM quantities.
- Report missing production IDs, conflicting outputs, unresolved upstream relationships, incomplete source data and numerical failure explicitly.
- Do not use a pairwise FGW cost alone as proof of a production cause.

# Deliverables

1. Two-pass production-ID tree implementation and automated grouping/scope/exclusion/cost/evidence tests, with a requirement-by-requirement PASS/FAIL report.
2. Actual occurrence tree interface and FGW adapter with preceding scope, allocation provenance and separate topology/lineage/cost completeness.
3. Standard FGW implementation and actual-tree comparison output.
4. Fixed numerical regression and validation report.
