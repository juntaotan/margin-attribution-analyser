# Agent Prompt: Restore Product Production Trees Using FIFO

# Objective

Starting from the identified abnormal branches, locate the affected final products, expand each product into its complete production tree, and restore the actual quantities and amounts belonging to that tree using material issue relationships and FIFO allocation.

Each final product instance must have its own tree. Preserve all leaf materials and intermediate semi-finished products or products. Supply the restored trees to [the standard FGW implementation](<CUDA Agent Prompt_ Implement Standard FGW in Five Steps.md>) for comparison between actual trees of the same product.

Follow these steps. For each step, specify inputs, outputs, and computation.

# Step 1: Locate the Affected Products

**Inputs:**

- Existing abnormal-branch results.
- Directed manufacturing relationships.
- Product, semi-finished product, and production-order identifiers.

**Outputs:**

- The affected products.
- The affected final product instances.
- Paths connecting each abnormal branch to those products.

**Computation:**

(1) Read the abnormal branches from the existing detection workflow. Reuse that workflow rather than replacing its detection algorithm.

(2) Traverse each branch upward through its downstream semi-finished products and products.

(3) Record all affected product nodes along the paths. Continue to the final product instances within the analysis scope.

(4) Preserve all reachable final product instances when one abnormal branch feeds several products.

(5) Deduplicate repeated discovery of the same final product instance while preserving every contributing abnormal-branch reference.

**Requirements:**

- Use material-to-product edge direction for upward traversal.
- Distinguish a product number from a production instance.
- Do not stop at the first affected product if other affected final products remain reachable.

# Step 2: Expand Each Product to All Leaf Materials

**Inputs:**

- Affected final product instances.
- Manufacturing relationships.
- Production and material issue records.

**Outputs:**

- One complete manufacturing tree per final product instance.
- Every leaf material and intermediate node.
- Stable node occurrences, directed edges, and root-to-leaf paths.

**Computation:**

(1) Set the final product instance as the tree root.

(2) Follow incoming material-to-product relationships downward from the root until all leaf materials have been reached.

(3) Record every intermediate semi-finished product or product and every manufacturing edge.

(4) Include all related branches, including branches outside the originally identified abnormal path.

(5) Preserve separate occurrences of the same material when it appears in different manufacturing positions, production orders, or attributed source-production instances.

(6) Retain source-record references so that each manufacturing relationship can later be linked to actual consumption.

**Requirements:**

- Tree display may run from root product to leaf materials; stored directed edges must retain the project's material-to-product convention.
- Do not replace the complete tree with only the original abnormal path.
- If source relationships are shared, preserve occurrence-level attribution when projecting them into separate product trees.
- Detect cycles and incomplete relationships. Do not silently produce a truncated complete tree.
- Keep inventory-layer bookkeeping in allocation metadata; do not add artificial manufacturing nodes solely because an issue spans several receipt layers.

# Step 3: Link Actual Issues to the Receiving Production Step

**Inputs:**

- Expanded product trees.
- Material issue records.
- Production-order and recipient semi-finished product/product identifiers.
- Source receipt or opening-inventory records.

**Outputs:**

- Actual issue records mapped to their receiving manufacturing steps.
- The actual quantity and recorded amount for each mapped issue.
- Eligible source-layer candidates for FIFO allocation.

**Computation:**

(1) Identify the receiving semi-finished product or product using the issue record's target and production-order relationship.

(2) Link the issued material to that specific manufacturing step.

(3) Use the actual issued quantity. Preserve the issue's recorded amount and valuation information for reconciliation.

(4) Resolve eligible source layers for that material and receiving step using the available source identifiers, inventory scope, unit, and transaction time.

(5) Include opening inventory and earlier source production when required by an issue. Do not restrict source availability to the final product's reporting period.

**Requirements:**

- Do not allocate by material number alone when the issue provides a specific recipient or order.
- Do not distribute an issue to unrelated products or branches.
- Preserve separate issue lines even when their material numbers are identical.
- Do not substitute theoretical BOM consumption for actual issue quantities.
- If the recipient or eligible source inventory cannot be resolved, return an unresolved record with its identifiers.

# Step 4: Allocate Actual Consumption Using FIFO

**Inputs:**

- Mapped actual issue records.
- Eligible receipt layers, including produced semi-finished product layers.
- Available quantities, attributed amounts, timestamps, and stable source identifiers.

**Outputs:**

- FIFO allocation records.
- Remaining quantity and amount for each source layer.
- Actual attributed consumption for each receiving step.
- Source lineage for each allocated quantity and amount.

**Computation:**

(1) Process inventory events in their recorded chronological order. Resolve equal timestamps using an explicit, stable sequence such as transaction sequence and record ID.

Include other recorded issues competing for the same source inventory, even when their receiving products are outside the selected trees. Their consumption still changes the source availability used by FIFO.

(2) For each issue, sort eligible available layers by receipt time and the same deterministic tie-breaking rule.

(3) Allocate from the oldest eligible layer first:

```text
allocatedQuantity = min(issueRemainingQuantity, layerRemainingQuantity)
```

(4) Continue to the next layer until the issue is fully allocated or eligible inventory is exhausted.

(5) Restore the attributed amount from the selected layer's quantity and remaining attributed amount:

```text
allocatedAmount = layerRemainingAmount
                * allocatedQuantity / layerRemainingQuantity
```

Use the layer state before this allocation. When consuming the entire remaining layer, assign its exact remaining amount.

(6) Update the issue remainder and source-layer remainder. Record each allocation's source layer, receiving step, issue line, quantity, amount, and FIFO sequence.

(7) For produced semi-finished products, carry their already restored input-material lineage into the output layer. Allocate that output layer to later receiving production steps using the same FIFO rule.

(8) Compare restored FIFO amounts with recorded issue amounts under the declared valuation scope. Report any discrepancy; do not silently overwrite source amounts or change the valuation method to force agreement.

**Requirements:**

- An issue cannot consume a future receipt or unavailable stock.
- Use a shared allocation ledger for the analysis dataset. Do not reset source availability independently for each product tree.
- When projecting a tree from existing allocations, reuse those allocations instead of consuming inventory again.
- Use decimal arithmetic for recorded quantities and monetary values. Apply the declared monetary precision and preserve allocation remainders.
- Preserve already known issue-to-recipient relationships. FIFO selects eligible source layers; it does not select an unrelated receiving product.
- Return an explicit shortage or reconciliation status if allocation cannot be completed.
- Reconcile reversals and returns with their source transactions before treating a snapshot as complete.

# Step 5: Restore Quantities and Amounts Through the Tree

**Inputs:**

- Complete manufacturing trees.
- FIFO allocations.
- Actual production output quantities.
- Input lineage of produced semi-finished product layers.

**Outputs:**

- Restored actual quantity and amount at every node occurrence and manufacturing relationship.
- The portion of each leaf material attributable to the final product instance.
- Traceable allocation lineage from leaf materials to the final product.

**Computation:**

(1) Start with actual leaf-material consumption mapped to its receiving production step.

(2) Trace the allocation lineage upward through intermediate semi-finished products and products.

(3) For each intermediate output layer, preserve the actual produced quantity and the restored amounts of its recorded inputs.

(4) When only part of a homogeneous intermediate output layer is consumed by a product tree, attribute the corresponding fraction of that output layer's recorded inputs and amounts:

```text
outputFraction = outputQuantityAttributedToTree / sourceOutputQuantity
inputQuantityAttributedToTree = recordedInputQuantity * outputFraction
inputAmountAttributedToTree   = restoredInputAmount * outputFraction
```

Record this within-layer proportional attribution convention separately from FIFO selection between layers. Do not infer a physical unit-level genealogy that the source records do not provide.

(5) Repeat through all intermediate production steps until the final product instance's tree has been fully attributed.

(6) Keep leaf quantities, intermediate quantities, final output quantities, and their units distinct. Parent output quantity comes from the production record and its attributed share; it is not the sum of child quantities with different units.

(7) Preserve restored subtree amounts at intermediate nodes for comparison and explanation. Compute financial totals from non-overlapping original allocation contributions, not by adding all cumulative node amounts.

**Requirements:**

- Every restored value must map back to an actual issue, source layer, or production record.
- The same source allocation share must not be counted in two product trees.
- Preserve the attributed share when a semi-finished product supplies multiple final products.
- Use explicit source allocation rules for co-products, losses, or other outputs that cannot use the homogeneous single-output convention. Report unresolved cases rather than inventing a split.
- Keep the monetary scope consistent. Include only recorded cost components covered by that scope.

# Step 6: Prepare the Trees for FGW Comparison

**Inputs:**

- Restored trees.
- Existing 12-dimensional material embeddings.
- Product-instance and node-occurrence identifiers.

**Outputs:**

- One FGW-ready tree per final product instance.
- CSR topology and embeddings aligned to the same node index order.
- Restored quantities, amounts, units, and source references aligned to those indices.
- Comparable actual-tree pairs for the same product.

**Computation:**

(1) Assign a stable root identifier and node-occurrence identifiers to each tree.

(2) Build CSR arrays from the manufacturing edges. Retain every intermediate node and leaf material.

(3) Load each material's existing 12-dimensional embedding and preserve its version. Do not redefine embedding dimensions in this stage.

(4) Keep the restored actual values and allocation references beside the graph input. Preserve the mapping from FGW indices back to tree occurrences and source records.

(5) Select multiple actual trees of the same product for comparison. Keep unit, monetary scope, and known production conditions available to assess comparability.

(6) Pass the trees to [CUDA Agent Prompt: Implement Standard FGW in Five Steps](<CUDA Agent Prompt_ Implement Standard FGW in Five Steps.md>). Follow its objective and weighting rules.

**Requirements:**

- Each final product instance retains its own complete tree.
- Do not aggregate separate trees into a product-number-only graph.
- Do not discard restored quantities and amounts during CSR conversion.
- Root and allocation metadata must survive node reindexing.

# Validation Requirements

1. Verify upward discovery when an abnormal branch affects more than one final product.
2. Verify downward expansion includes all intermediate nodes and all leaf branches.
3. Verify each issue is linked to its recorded receiving semi-finished product or product.
4. Verify FIFO ordering, chronological availability, and deterministic ties.
5. Verify source-layer quantity and amount conservation.
6. Verify one issue spanning multiple source layers.
7. Verify one source layer serving multiple final product trees without duplicate consumption.
8. Verify partial semi-finished output attribution and multi-level restoration.
9. Verify repeated materials in different branches retain separate occurrences.
10. Verify shortage, unresolved recipient, amount discrepancy, return, and incomplete-source statuses.
11. Verify CSR indices, embeddings, restored values, and source mappings remain aligned.

# Fixed FIFO Allocation Test

**Inputs:**

P1 and P2 are separate final product instances of the same product type. Their issues share one inventory allocation ledger.

```text
Material B, one inventory scope and one unit:

Older layer: quantity = 100, amount = 200
Newer layer: quantity = 100, amount = 300

Earlier issue to product P1: quantity = 120, recorded amount = 260
Later issue to product P2:   quantity = 30,  recorded amount = 90
```

**Expected outputs:**

```text
P1:
  Older layer allocation = quantity 100, amount 200
  Newer layer allocation = quantity 20,  amount 60
  Total                  = quantity 120, amount 260

P2:
  Newer layer allocation = quantity 30, amount 90
  Total                  = quantity 30, amount 90

Remaining inventory:
  Older layer = quantity 0,  amount 0
  Newer layer = quantity 50, amount 150

Conservation:
  Quantity: 200 = 120 + 30 + 50
  Amount:   500 = 260 + 90 + 150
```

Both issues must share the same allocation ledger. Verify their recipient trees preserve the respective allocation references.

# Additional Test: Restore Through a Semi-Finished Product

**Inputs:**

```text
Semi-finished production instance S:
  Actual output quantity = 20
  Actual material B input = quantity 120, restored amount 260

Final product instance P1:
  Actual output quantity = 4
  Actual S consumption = 8

Final product instance P2:
  Actual output quantity = 3
  Actual S consumption = 6
```

Use the same single homogeneous S output layer for both receiving steps. Preserve its original B issue allocations as input lineage.

**Expected outputs:**

```text
P1 tree:
  Attributed S quantity = 8
  Attributed B quantity = 48
  Restored material amount = 104

P2 tree:
  Attributed S quantity = 6
  Attributed B quantity = 36
  Restored material amount = 78

Remaining S layer:
  Quantity = 6
  Amount = 78
  Embodied B quantity = 36

S output quantity: 20 = 8 + 6 + 6
B input lineage quantity: 120 = 48 + 36 + 36
Restored material amount: 260 = 104 + 78 + 78
```

The embodied B in remaining S is already consumed input lineage, not available raw-material inventory. Verify that projection into the P1 and P2 trees does not consume those original B issues again or add cumulative parent amounts to the same material amount twice.

# Deliverables

- Affected-product discovery and complete product-tree expansion.
- Actual issue-to-production-step mapping.
- FIFO quantity and amount allocation.
- Multi-level actual quantity and amount restoration.
- One complete attributed tree per final product instance.
- FGW input adapter with stable source mappings.
- Automated allocation/restoration tests and a PASS/FAIL report.

After these deliverables pass their checks, execute the second and third instruction files listed in [the agent workflow](<Production Anomaly Analysis — Agent Workflow.md>).
