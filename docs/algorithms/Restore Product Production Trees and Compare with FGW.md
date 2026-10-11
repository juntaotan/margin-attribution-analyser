# Restore Product Production Trees and Compare with FGW

# Objective

Implement production-branch analysis by building one actual consumption tree per production ID from its recorded issues, then comparing different production instances of the same product with standard balanced Fused Gromov-Wasserstein (FGW). The user-confirmed mapping is `productionId = inventory_usage.order_no`; do not perform FIFO allocation or infer missing batches.

Follow the three instruction files below in order. Treat them as execution instructions for the implementation agent.

# File 1: Build Product Production Trees by Production ID

Read [Agent Prompt: Build Product Production Trees by Production ID](<../Agent Prompt_ Restore Product Production Trees Using FIFO.md>). The historical filename is retained for link compatibility.

**Inputs:** Selected production IDs or existing abnormal-branch results, recorded material issues and production output metadata, directed manufacturing relationships, and explicit upstream production links where available.

**Outputs:** One actual consumption tree per production ID, recorded quantities and amounts, CSR topology, issue/source mappings, declared topology scope and completeness statuses. Full multilevel lineage requires explicit upstream production links.

**Computation:**

(1) Select requested production IDs directly, or reuse abnormal-branch discovery to locate affected products and candidate production instances.

(2) Retrieve all available issue records for each selected production ID, including records outside the selection period. Keep different IDs separate even when their product numbers agree.

(3) Build its root and actual material occurrences using the recorded receiving relationships. Expand intermediates only through explicit source-production links; retain unresolved intermediates as boundary nodes with incomplete-lineage status.

(4) Aggregate recorded quantities and material amounts with decimal arithmetic and retained issue references. Resolve repeated output totals once, report conflicts and keep output valuation separate. Do not revalue from inventory layers or add cumulative subtree amounts twice.

(5) Preserve one tree per production ID and prepare the existing FGW interface with aligned embeddings and source mappings. Leave legacy FIFO references empty. Compare only matching topology scopes with explicit eligibility statuses.

Complete the grouping, tree and evidence checks in File 1 before supplying accepted inputs to File 2. A documentation-only revision does not execute these subsequent implementation stages.

# File 2: Implement Standard FGW in Five Steps

Read [CUDA Agent Prompt: Implement Standard FGW in Five Steps](<CUDA Agent Prompt_ Implement Standard FGW in Five Steps.md>).

**Inputs:** Two eligible actual product trees from File 1 at the same topology scope, CSR topology, and caller-supplied 384-dimensional material content embeddings aligned with tree node indices.

**Outputs:** Correspondence matrix, material cost, structure cost, FGW cost, iteration count, and convergence status.

**Computation:**

(1) Compare different production IDs of the same product. Preserve production-instance identity and completeness metadata when selecting tree pairs.

(2) Execute the five prescribed FGW steps: distance matrices, fixed node weights, initial cost, Frank–Wolfe optimization, and convergence verification.

(3) Map the correspondence back to product-tree occurrences and branches. Include recorded quantities, amounts, production IDs and issue/source references in the comparison output.

(4) Apply the objective, distance definitions, weights, and numerical settings specified in File 2. Restored quantity/amount metadata does not implicitly change that objective. Do not introduce additional attribute penalties, a different transport formulation, or an automatic anomaly threshold without an explicit specification.

# File 3: Validate the FGW Numerical Implementation

Read [FGW CUDA Implementation — Numerical Test Case](<FGW CUDA Implementation — Numerical Test Case.md>).

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

1. Production-ID tree implementation and automated grouping/evidence tests.
2. Actual consumption tree interface and FGW adapter with source/completeness metadata.
3. Standard FGW implementation and actual-tree comparison output.
4. Fixed numerical regression and validation report.
