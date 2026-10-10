# CUDA Agent Prompt: Implement Standard FGW in Five Steps

# Background

Assume that complete production trees with FIFO-restored actual quantities and amounts are already available. Compare different material compositions and production structures of the **same product** using FGW.

# Objective

The feature of finding out the abnormal transactions uses the **standard balanced Fused Gromov-Wasserstein (FGW)**  algorithm in CUDA and OpenCL for comparing manufacturing graphs with different node counts and unordered structures. Each node has an existing 12-dimensional material embedding. Graph topology is stored in CSR format.

Follow these five steps. For each step, specify inputs, outputs, and computation.

# Step 1: Calculate Distance Matrices

**Inputs:**  Two CSR graphs (`G1`​, `G2`​) and node embeddings `[N, 12]`​ and `[M, 12]`.

**Outputs:**  Material distance matrix `M_attr [N, M]`​and structural distance matrices `D1 [N, N]`​, `D2 [M, M]`.

**Computation:**

(1) Calculate squared Euclidean distances between cross-graph node embeddings.

(2) Calculate all-pairs shortest-path distances within each graph, initially treating edges as undirected.

# Step 2: Initialise Weights

**Inputs:**  Node counts `N`​, `M`.

**Outputs:**  Weight vectors `p`​, `q`​ and correspondence matrix `P`.

**Computation:**

(1) Assign uniform weights: `p[i] = 1/N`​, `q[j] = 1/M`.

(2) Initialise `P = outer(p, q)`. Support an explicitly supplied feasible initial matrix for additional tests or multiple-start comparisons. The fixed numerical test must use its prescribed uniform outer product.

(3) Keep `p`​ and `q` fixed throughout optimisation.

# Step 3: Calculate Initial FGW Cost

**Inputs:**  `M_attr`​, `D1`​, `D2`​, `P`​, `alpha`.

**Outputs:**  `materialCost`​, `structureCost`​, `fgwCost`.

**Computation:**

(1) Calculate material cost using weighted embedding distances.

(2) Calculate structure cost using weighted squared differences between intra-graph distances.

(3) Combine both costs using `alpha`.

(4) Use efficient matrix operations instead of four nested loops.

Use this exact objective and its constraints:

```text
materialCost  = <M_attr, P>
structureCost = sum_{i,k,j,l} (D1[i,k] - D2[j,l])^2 * P[i,j] * P[k,l]
fgwCost       = (1-alpha) * materialCost + alpha * structureCost

P >= 0
P * ones(M) = p
transpose(P) * ones(N) = q
```

For symmetric structural matrices and a feasible transport matrix, precompute the fixed-marginal term and evaluate the structure cost as:

```text
C = outer(elementwise_square(D1) * p, ones(M))
  + outer(ones(N), elementwise_square(D2) * q)
H(P) = C - 2 * D1 * P * transpose(D2)
structureCost = <H(P), P>
```

Here `<A,B>` is the elementwise inner product, `*` denotes matrix/vector multiplication, and `elementwise_square` means elementwise squaring. Report the objective itself without introducing a square root or a factor of one half.


# Step 4: Optimise P Using Frank–Wolfe

**Inputs:**  Current `P`​, fixed `p`​, `q`​, distance matrices, `alpha`.

**Outputs:**  Updated `P`, new FGW cost.

**Computation:**

(1) **Gradient:**  Differentiate the FGW objective with respect to the current `P`. Accelerate matrix operations using CUDA.

(2) **Linear OT:**  Solve the balanced linear transport problem using the gradient to obtain candidate matrix `S`.

(3) **Direction:**  Calculate `deltaP = S - P`.

(4) **Line Search:**  Find the optimal step size `gamma` between 0 and 1.

(5) **Update:**  Calculate `newP = P + gamma * deltaP`, then recalculate FGW cost.


For symmetric structural matrices, evaluate the original objective's gradient at the feasible current matrix using:

```text
G = (1-alpha) * M_attr + 2 * alpha * H(P)
S = argmin <G, S>
    subject to S >= 0, S*ones(M) = p, transpose(S)*ones(N) = q
FW_Gap = <G, P-S>
```

Use an exact balanced transport LP solver to numerical tolerance. Verify feasibility and optimality. Do not replace this problem with one-to-one assignment or entropic Sinkhorn. In additional fixtures with multiple optimal linear solutions, compare linear cost and marginals rather than requiring identical matrices.

Use exact quadratic line search with `deltaP = S-P`:

```text
F(P + gamma*deltaP) = F(P) + b*gamma + a*gamma^2
a = -2 * alpha * <D1 * deltaP * transpose(D2), deltaP>
b = <G, deltaP>
```

If `a > 0`, consider the clipped stationary point and both endpoints. If `a <= 0`, compare the endpoints. Handle near-zero coefficients explicitly. Verify that the selected step does not increase the true objective beyond numerical tolerance.

Repeat using the updated `P`​. Recompute the gradient and `S` every iteration.

# Step 5: Check Convergence

**Inputs:**  Previous/current `P`, previous/current FGW cost, convergence thresholds.

**Outputs:**  Final `P`, FGW cost, iteration count, convergence status.

**Computation:**

(1) Recompute the gradient, exact linear OT result, and FW gap at the current matrix. Verify finite values, nonnegative transport weights to numerical tolerance, and original marginals.

(2) Require `FW_Gap <= tolGapAbs + tolGapRel * max(abs(fgwCost), costScaleFloor)` for first-order convergence. Record absolute and relative cost changes as additional stopping diagnostics. A significantly negative gap is a numerical or OT failure.

(3) Report `FW_GAP_REACHED`, `COST_STALLED`, `MAX_ITERATIONS`, or `NUMERICAL_FAILURE` separately. Reaching the iteration limit alone is not convergence. Check the gap at the returned matrix after the final update.

(4) A zero gap certifies first-order stationarity, not global optimality. Support feasible multiple starts when required by the comparison or node-ordering test. Keep their configuration and results in the report.

Initial parameters:

```
alpha         = 0.8
tolAbs        = 1e-6
tolRel        = 1e-5
maxIterations = 200
tolGapAbs     = 1e-6
tolGapRel     = 1e-5
costScaleFloor = 1e-12
```

# Implementation Requirements

- Use exactly three production CUDA implementation files as specified below. Keep the mathematical algorithm in one file and separate it from CUDA execution management.
- Use **CUDA** for computationally intensive operations, especially batched matrix multiplication and gradient computation.
- Keep intermediate matrices in GPU memory where practical.
- Support unequal graph sizes and batched graph comparisons.
- Preserve standard balanced FGW mathematics; do not silently replace exact Linear OT with Sinkhorn.
- Validate numerical correctness against ​**Python Optimal Transport (POT)** .
- Record the POT version, explicit floating-point inputs, initial matrix, `loss_fun="square_loss"`, `symmetric=True`, `alpha`, `armijo=False`, and tolerances for reference validation.
- Keep root, node-occurrence, quantity, amount, and FIFO source mappings available in the comparison output.
- State which operations run on CPU or GPU, including exact linear OT. Benchmark transfers and synchronization as part of complete runtime.
- Verify gradients using feasible zero-row-sum and zero-column-sum directions against the original quadratic objective.
- Reject invalid CSR or disconnected inputs rather than propagating infinite structural distances.
- State CUDA and OpenCL delivery/test coverage separately.
- Benchmark the complete CUDA implementation against an optimized multicore CPU baseline.

# File Organisation

Use these filenames, following the existing CUDA algorithm/engine convention:

| File | Responsibility |
| --- | --- |
| `fgw.cu` | Keep all FGW mathematical routines and computational kernels in this single algorithm file: attribute and structural distances, costs, gradients, the linear OT subproblem logic, line-search calculations, and convergence criteria. |
| `fgw_engine.cu` | Manage CUDA execution: device selection, memory allocation and transfers, thread-block/grid configuration, kernel launches, streams, synchronisation, GPU library handles, and invocation of the algorithm routines. |
| `fgw_engine.cuh` | Declare the engine interface, graph inputs, configuration, results, and required routine/kernel interfaces. Keep implementation bodies in the two `.cu` files. |

Do not split the FGW mathematics across additional implementation files or duplicate its formulas in the engine. The engine may control execution and iteration, but must call the algorithm file's routines for mathematical calculations and convergence decisions. Test sources and build files are separate from this three-file implementation limit.

**Deliverables:** CUDA implementation, actual-product-tree comparison output with source mappings, and CUDA test.
