# Abnormal branch implementation

Parent: [Main analysis design](Main_Analysis_Design.md). Step 2 consumes [period CSR](CSR_Implementation.md) and supplies branch/selection evidence to [production restoration](FGW_Build_Production_Trees.md). The [branch tests](Abnormal_Branches_Test_Case.md) cover cost changes and combined structure/cost changes; existing acceptance requirements remain in the parent.

## Inputs and validation

Accept actual/comparable material-to-product graphs, exact decimal `leafThreshold` and `stopThreshold`, and the parent's execution policy. Require nonnegative finite thresholds exactly representable at six decimal places, signed-long micro-unit bounds, unique inventory IDs, aligned/bounded CSR and DAG topology. Keep the actual graph's original node/edge identities and costs available for reconstruction.

## Implementation process

1. Use `BomGraphPreparer` to align comparable costs by inventory ID. Calculate actual-minus-comparable differences only when both values are present; retain explicit missing/noncomparable and actual-only/comparable-only evidence.
2. Identify actual graph material-side leaves (nodes with no incoming material child). Seed leaves whose absolute comparable difference meets `leafThreshold`. Encode differences and stop threshold in exact six-decimal signed-long micro-units.
3. Mark ancestors reachable from selected leaves and count only their participating children. Preserve the full original node-index space and original material-to-product adjacency.
4. Run the selected frontier adapter with the existing `BomUpwardEngine` contract. Terminals are seeds, not stop-threshold candidates. An ancestor becomes eligible after all counted children propagate. Select it when its supplied comparable absolute node difference meets `stopThreshold`; otherwise continue upwards. A selected child stops propagation and blocks its ancestors. Do not accumulate child differences or replace supplied node values with a new sum.
5. Use shared `BomFrontierReconciler` reconstruction against the original actual graph to retain material-to-selected-boundary paths. Preserve graph role, selected boundary, original positions and path/source references.
6. Return branch evidence and stage execution/availability statuses. With no paths, the workflow returns `NO_ABNORMAL_BRANCHES` before production selection or inference; it must not invoke date-only inspection with an empty path list and accidentally select all instances.

## Output and hand-off

Paths identify candidates, not proven abnormal production instances. The workflow follows actual recorded receiving relationships, excludes synthetic COGS from production identity selection, and discovers same-product instances independently in both periods. See [candidate and pair selection](Analysis_Workflow_Implementation.md#candidate-and-pair-selection).

Current discovery is actual-side cost-difference pruning. It does not establish symmetric removed/added-material detection, approved substitution or causal attribution. Preserve missing-cost evidence rather than reporting normality.

## Execution and failures

Introduce the small `FrontierEngine` boundary and inject Java/native adapters without duplicating path reconstruction. Select Java/CUDA/OpenCL using the parent's caller-supplied policy. Report requested/actual backend, unavailable bindings and permitted fallback; never switch GPU families silently. Preserve threshold semantics and exact micro-units across paths. Applicable native tests and parity checks remain required when accelerator execution changes.
