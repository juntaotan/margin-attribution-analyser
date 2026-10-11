# FGW numerical test case

## Workflow position

Child of [FGW algorithm design](FGW_Algorithm_Design.md), validating [the five-step solver](FGW_Implementation_Five_Steps.md). Keep the fixed synthetic inputs, distance definitions and expected values unchanged. Production-tree behaviour is validated separately by [tree acceptance tests](FGW_Build_Production_Trees_Test_Case.md#required-tests-with-passed-inputs-and-no-database-reads); production records do not replace this numerical regression.

## Objective

Validate the CUDA implementation of standard balanced Fused Gromov-Wasserstein (FGW).

The test must verify all five computation steps, including distance matrices, initialisation, FGW cost, gradient, Frank–Wolfe optimisation, and convergence.

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
- Frank–Wolfe optimisation with line search.
- No additional distance normalisation.

---

## 2. Step 1 — Calculate Distance Matrices

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

## 3. Step 2 — Initialise Node Weights

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

The node weights `p`​ and `q` must remain unchanged throughout optimisation.

---

## 4. Step 3 — Calculate Initial FGW Cost

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

## 5. Step 4 — Frank–Wolfe Optimisation

### 5.1 Calculate Gradient

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

**Expected output:**

```text
gamma = 1.0
```

**Computation:**

Find the step size that minimises the true FGW objective along the update direction.

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

Verify that changing the storage order of sibling nodes does not change the optimised FGW distance.

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

Because FGW is nonconvex, use a suitable initialisation or multiple starts if necessary to verify the zero-cost solution.

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

For FP32, also verify marginal constraints and allow for solver-specific rounding behaviour.

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

**Do not begin GPU performance optimisation until all numerical correctness tests pass.**

The final test report must contain:

- Actual and expected matrices.
- Maximum absolute numerical error.
- FGW cost for each iteration.
- Marginal-constraint errors.
- Final convergence status.
- PASS/FAIL summary.
