# Period CSR acceptance tests

Implementation: [CSR construction](CSR_Implementation.md). These user-confirmed tests start from explicitly passed issue-document rows and the minimal sales records needed by the existing target-selection contract. Use in-memory providers; do not read a database or inject a finished CSR in place of construction.

## Fixed tree and source records

Use one namespace, one currency, compatible units and complete input coverage. All rows have distinct `(id,date)` identities and document references inside the selected period. Sell ten units of A with sales cost 180. Receiving instances A-P, B-P and C-P each have confirmed repeated output quantity 10. Supply these four original issue rows:

| Issue | Receiver | Receiving production ID | Material | Quantity | Amount |
| --- | --- | --- | --- | ---: | ---: |
| L1 | A | A-P | B | 10 | 100 |
| L2 | A | A-P | C | 10 | 80 |
| L3 | B | B-P | D | 20 | 100 |
| L4 | C | C-P | E | 10 | 80 |

```text
A
├── B
│   └── D
└── C
    └── E
```

Recorded edges are B→A, C→A, D→B, E→C and A→__COGS__. The current node order is ascending inventory ID. Assert the full arrays, not only node/edge counts:

```text
nodes      = [A, B, C, D, E, __COGS__]
offset     = [0, 1, 2, 3, 4, 5, 5]
successors = [5, 0, 0, 1, 2]
```

Assert B quantity/amount 10/100, C 10/80, D 20/100, E 10/80 and COGS amount 180. A's output quantity is 10, not 20 from its two repeated issue-row output values. Do not infer A's period node cost from child sums when the existing contract does not provide it. Assert source-to-position mappings through the production construction path.

## Boundary cases

| ID | Input variation | Required assertions |
| --- | --- | --- |
| C01 | Fixed tree above | Exact node order, offset and successors; each original relationship represented. |
| C02 | Empty sales and issue records | Empty node/successor arrays and offset `[0]`; no invented COGS node. |
| C03 | A sold, no issue rows | Nodes `[A,__COGS__]`, offset `[0,1,1]`, successors `[1]`; missing production evidence is not declared restored. |
| C04 | Split L3 into two distinct rows: quantities 8/12, amounts 40/60 | D remains quantity 20, amount 100 with one D→B adjacency; both source identities survive. |
| C05 | Same material D supplies B and C | One period D node with both outgoing relationships and summed recorded flows; separate receiving references survive. Assert both successors without imposing a new sort on the existing insertion-ordered adjacency. |
| C06 | Shuffle otherwise identical input rows | Same node order, degrees, quantities, amounts and decoded edge set. For a multi-successor row accept valid adjacency-order permutations unless the public contract specifies ordering. |
| C07 | Rows before/on/after each date boundary | Inclusive boundary records included, outside records excluded from this period; later complete-instance loading has its separate contract. |
| C08 | Compatible decimal amounts 0.1/0.2 and 9007199254740993.01/0.02 | Exact totals 0.3 and 9007199254740993.03, without binary floating-point loss. |
| C09 | Shared intermediate reachable through several products | Traversal terminates; original records are not multiplied by repeated dependency visits. |
| C10 | Missing date, reversed range, missing material/quantity/amount, self-consumption and cycle variants | Invalid dates fail explicitly. Missing financial evidence must not become an invented complete zero. Record current skipped/self-consumption behaviour separately; cyclic/invalid discovery cannot be reported eligible for DAG pruning. Verify the validating boundary and explicit diagnostics rather than assume every raw handler rejects every malformed row. |

For every returned graph assert offset length `nodeCount+1`, initial zero, monotonicity, final offset equal to successor length and bounded successor indices. Decode every row back to original material-to-product relationships. Use the actual handlers/workflow and a deterministic input boundary, not a test-only graph builder.

## Execution and report

Implement automated Java tests and run the prescribed Java 21 Maven test command in AGENTS.md. Report C01–C10 and parameterised variants as PASS/FAIL with actual arrays, exact amounts and executed command. These are specified tests, not results established by this documentation edit.
