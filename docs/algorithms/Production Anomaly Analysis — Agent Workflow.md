# Agent Workflow: Restore Product Production Trees and Compare with FGW

# Objective

Implement production-branch analysis by restoring the complete actual production tree of each affected final product using issue-record relationships and FIFO, then comparing actual trees of the same product with standard balanced Fused Gromov-Wasserstein (FGW).

Follow the three instruction files below in order. Treat them as execution instructions for the implementation agent.

# File 1: Restore Product Production Trees Using FIFO

Read [Agent Prompt: Restore Product Production Trees Using FIFO](<../Agent Prompt_ Restore Product Production Trees Using FIFO.md>).

**Inputs:** Existing abnormal-branch results, directed material-to-product relationships, production records, material issue records, and inventory/production receipt layers.

**Outputs:** One complete attributed tree per final product instance, including every intermediate node and leaf material, restored quantities and amounts, CSR topology, and source-allocation references.

**Computation:**

(1) Start from the identified abnormal branches and trace upward to the products they affect.

(2) Expand each affected final product downward to all leaf materials. Record every intermediate semi-finished product, product, and manufacturing relationship.

(3) Use each material issue record's recipient semi-finished product or product to identify the manufacturing relationship receiving the actual consumption.

(4) Allocate consumption to eligible source layers using FIFO. Trace the attributed leaf consumption upward and restore the quantities and amounts belonging to each product tree.

(5) Preserve a separate tree for each final product instance. Keep quantities, amounts, and source references attached to the corresponding node occurrences.

Complete the allocation and restoration checks in File 1 before supplying a tree to File 2.

# File 2: Implement Standard FGW in Five Steps

Read [CUDA Agent Prompt: Implement Standard FGW in Five Steps](<CUDA Agent Prompt_ Implement Standard FGW in Five Steps.md>).

**Inputs:** Two complete actual product trees from File 1, CSR topology, and caller-supplied 384-dimensional material content embeddings aligned with tree node indices.

**Outputs:** Correspondence matrix, material cost, structure cost, FGW cost, iteration count, and convergence status.

**Computation:**

(1) Compare multiple actual trees of the same product. Preserve each final product instance's identity when selecting tree pairs.

(2) Execute the five prescribed FGW steps: distance matrices, fixed node weights, initial cost, Frank–Wolfe optimization, and convergence verification.

(3) Map the resulting correspondence back to the original product-tree nodes and branches. Include the restored actual quantities, amounts, and FIFO source references in the comparison output.

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
- Keep File 1 responsible for actual consumption attribution and FIFO restoration.
- Keep File 2 responsible for the standard FGW pairwise calculation.
- Keep File 3 responsible for the fixed FGW numerical regression.
- Preserve product-instance, node-occurrence, production-order, issue-line, and FIFO-allocation identities across all stages.
- Do not merge separate product instances or separate branches solely because they share a material number.
- Do not replace actual issue quantities with theoretical BOM quantities.
- Report unresolved allocation, incomplete source data, and numerical failure explicitly.
- Do not use a pairwise FGW cost alone as proof of a production cause.

# Deliverables

1. FIFO restoration implementation and automated allocation tests.
2. Attributed product-tree interface and FGW input adapter.
3. Standard FGW implementation and actual-tree comparison output.
4. Fixed numerical regression and validation report.
