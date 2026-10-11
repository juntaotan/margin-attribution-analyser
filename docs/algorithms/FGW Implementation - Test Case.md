# FGW Implementation - Test Case

## Workflow Position

Execute this as File 3 of [the production analysis agent workflow](<Production Anomaly Analysis — Agent Workflow.md>) to validate [the FGW implementation](<CUDA Agent Prompt_ Implement Standard FGW in Five Steps.md>).

Use the fixed synthetic inputs and expected values below. Do not replace this regression with production-ID data or alter its distance definitions. Validate [production-ID tree construction](<../Agent Prompt_ Restore Product Production Trees Using FIFO.md>) separately through its prescribed grouping, tree and evidence tests. The linked file retains its historical filename; its current specification uses `inventory_usage.order_no` as the confirmed production ID and requires no FIFO allocation.

## Objective

Validate the CUDA implementation of standard balanced Fused Gromov-Wasserstein (FGW).

The test must verify all five computation steps, including distance matrices, initialization, FGW cost, gradient, Frank–Wolfe optimization, and convergence.

**Do not modify the expected results. If the implementation produces different values, investigate the cause.**

---

## 1. Test Data

### Graph 1: Normal Production

```text
A
└── B
```

- Nodes: `[A, B]`
- Node count: `N = 2`

CSR representation:

```java
rowOffsets = [0, 1, 1]
colIndices = [1]
```

### Graph 2: Actual Production

```text
A
└── X
    └── B
```

- Nodes: `[A, X, B]`
- Node count: `M = 3`

CSR representation:

```java
rowOffsets = [0, 1, 2, 2]
colIndices = [1, 2]
```

### Node Embeddings

Each node has a 384-dimensional material content embedding. The fixed fixture uses zero padding so that changing the embedding dimension does not change any expected distance or FGW result.

```text
A = [0.0] * 384
X = [1.0] + [0.0] * 383
B = [2.0] + [0.0] * 383
```

### Configuration

```text
alpha         = 0.8
tolAbs        = 1e-6
tolRel        = 1e-5
maxIterations = 200
```

Requirements:

- Standard balanced FGW.
- Squared Euclidean material distance.
- Squared structural-distance loss.
- Undirected shortest-path structural distances.
- Uniform node weights.
- Exact linear optimal transport subproblem.
- Frank–Wolfe optimization with line search.
- No additional distance normalization.

---

## 2. Step 1 — Calculate Distance Matrices

### Inputs

- Graph 1 CSR
- Graph 2 CSR
- Node embeddings

### Expected Outputs

Material distance matrix:

```text
M_attr = [
    [0.0, 1.0, 4.0],
    [4.0, 1.0, 0.0]
]
```

Graph 1 structural distance:

```text
D1 = [
    [0.0, 1.0],
    [1.0, 0.0]
]
```

Graph 2 structural distance:

```text
D2 = [
    [0.0, 1.0, 2.0],
    [1.0, 0.0, 1.0],
    [2.0, 1.0, 0.0]
]
```

### Validation

Verify every matrix element against the expected values.

---

## 3. Step 2 — Initialize Node Weights

### Inputs

```text
N = 2
M = 3
```

### Expected Outputs

```text
p = [0.5, 0.5]

q = [
    0.3333333333,
    0.3333333333,
    0.3333333333
]
```

Initial correspondence matrix:

```text
P0 = [
    [0.1666666667, 0.1666666667, 0.1666666667],
    [0.1666666667, 0.1666666667, 0.1666666667]
]
```

### Validation

```text
Row sums    = [0.5, 0.5]
Column sums = [1/3, 1/3, 1/3]
Total sum   = 1.0
```

The node weights `p`​ and `q` must remain unchanged throughout optimization.

---

## 4. Step 3 — Calculate Initial FGW Cost

### Inputs

- `M_attr`
- `D1`
- `D2`
- `P0`
- `alpha = 0.8`

### Expected Outputs

```text
Material Cost  = 1.6666666667
Structure Cost = 0.9444444444
FGW Cost       = 1.0888888889
```

### Validation

Verify all three values independently.

Do not validate only the final FGW cost.

---

## 5. Step 4 — Frank–Wolfe Optimization

### 5.1 Calculate Gradient

**Inputs:**

- Current `P0`
- `M_attr`
- `D1`
- `D2`
- `alpha`

**Expected output:**

```text
G0 = [
    [1.8666666667, 1.0000000000, 2.6666666667],
    [2.6666666667, 1.0000000000, 1.8666666667]
]
```

**Computation:**

Calculate the derivative of the standard FGW objective with respect to the current correspondence matrix.

Use the efficient matrix formulation, including the `D1 * P * transpose(D2)` operation.

Validate every gradient element.

### 5.2 Solve Linear Optimal Transport

**Inputs:**

- `G0`
- Fixed weights `p`​ and `q`

**Expected output:**

```text
S0 = [
    [0.3333333333, 0.1666666667, 0.0000000000],
    [0.0000000000, 0.1666666667, 0.3333333333]
]
```

**Computation:**

Solve the balanced linear optimal transport problem using the gradient as its cost matrix.

Verify that `S0` satisfies the original marginal constraints.

Expected initial Frank–Wolfe gap:

```text
FW_Gap0 = 0.2666666667
```

### 5.3 Calculate Update Direction

**Inputs:**

- `P0`
- `S0`

**Expected output:**

```text
deltaP = [
    [ 0.1666666667, 0.0, -0.1666666667],
    [-0.1666666667, 0.0,  0.1666666667]
]
```

**Computation:**

```text
deltaP = S0 - P0
```

### 5.4 Perform Line Search

**Inputs:**

- `P0`
- `deltaP`
- FGW distance matrices
- `alpha`

**Expected output:**

```text
gamma = 1.0
```

**Computation:**

Find the step size that minimizes the true FGW objective along the update direction.

Update the correspondence matrix:

```text
P1 = P0 + gamma * deltaP
```

Expected updated matrix:

```text
P1 = [
    [0.3333333333, 0.1666666667, 0.0000000000],
    [0.0000000000, 0.1666666667, 0.3333333333]
]
```

### 5.5 Recalculate FGW Cost

**Inputs:**

- Updated `P1`
- `M_attr`
- `D1`
- `D2`
- `alpha`

**Expected outputs:**

```text
Material Cost  = 0.3333333333
Structure Cost = 0.5000000000
FGW Cost       = 0.4666666667
```

Cost comparison:

```text
Initial FGW Cost = 1.0888888889
Updated FGW Cost = 0.4666666667
```

Verify that the updated cost is lower.

---

## 6. Step 5 — Convergence Verification

Recalculate the gradient using `P1`.

### Expected Gradient

```text
G1 = [
    [0.8000000000, 1.0000000000, 3.7333333333],
    [3.7333333333, 1.0000000000, 0.8000000000]
]
```

### Expected Linear OT Result

```text
S1 = [
    [0.3333333333, 0.1666666667, 0.0000000000],
    [0.0000000000, 0.1666666667, 0.3333333333]
]
```

Expected:

```text
S1 == P1

FW_Gap1 = 0.0
```

The algorithm should terminate because no further first-order improvement direction remains.

### Final Expected Results

```text
Final P = [
    [0.3333333333, 0.1666666667, 0.0000000000],
    [0.0000000000, 0.1666666667, 0.3333333333]
]

Final FGW Cost = 0.4666666667

Converged = true
```

The reported iteration count may depend on whether the implementation counts the final convergence-check iteration.

---

## 7. Additional Test — Node Ordering Invariance

Verify that changing the storage order of sibling nodes does not change the optimized FGW distance.

Graph A:

```text
    A
   / \
  B   C
```

Graph B:

```text
    A
   / \
  C   B
```

Requirements:

- Use identical embeddings for identical materials.
- Preserve the same graph connectivity.
- Only change node indexing or CSR storage order.

Expected optimal FGW cost:

```text
FGW Cost = 0.0
```

Because FGW is nonconvex, use a suitable initialization or multiple starts if necessary to verify the zero-cost solution.

---

## 8. Acceptance Criteria

|Test|Expected Result|
| ------------------------------| -----------------------|
|Material distance matrix|Exact expected values|
|Structural distance matrices|Exact expected values|
|Initial P|Uniform outer product|
|Initial FGW cost|1.0888888889|
|Initial gradient|Matches G0|
|First Linear OT result|Matches S0|
|First FW gap|0.2666666667|
|Line search gamma|1.0|
|Updated P|Matches P1|
|Updated FGW cost|0.4666666667|
|Second gradient|Matches G1|
|Final FW gap|0.0|
|Convergence|True|

Recommended numerical tolerances:

```text
FP64 = 1e-8
FP32 = 1e-5
```

For FP32, also verify marginal constraints and allow for solver-specific rounding behavior.

### Required Checks

1. Compare CUDA results with an independent CPU reference.
2. Validate matrix dimensions at every step.
3. Verify that P, S, and updated P satisfy the marginal constraints.
4. Check for NaN, Inf, and negative transport weights.
5. Verify that the gradient matches numerical directional derivatives.
6. Verify that line search does not increase the FGW objective.
7. Validate against Python Optimal Transport (POT).
8. Report any discrepancy between actual and expected values.

---

## 9. Instructions for the CUDA Agent

Implement this numerical test as an automated regression test.

For every step:

1. Execute the CUDA implementation.
2. Collect the calculated output.
3. Compare it with the expected result.
4. Report PASS or FAIL.
5. If a test fails, identify the failing computation before proceeding.

**Do not begin GPU performance optimization until all numerical correctness tests pass.**

The final test report must contain:

- Actual and expected matrices.
- Maximum absolute numerical error.
- FGW cost for each iteration.
- Marginal-constraint errors.
- Final convergence status.
- PASS/FAIL summary.
