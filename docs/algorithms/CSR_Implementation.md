# Period CSR implementation

Parent: [Main analysis design](Main_Analysis_Design.md). Step 1 builds discovery graphs from source records; [step 2](Abnormal_Branches_Implementation.md) consumes them. The [CSR tests](CSR_Test_Case.md) define the confirmed issue-record fixture and boundary cases. Existing acceptance requirements remain in the parent.

## Inputs and preconditions

Accept actual/comparable inclusive date ranges and read-only source providers for sold-product selection, sales valuation and inventory issues. Reject absent dates or a start after its end. Load both periods from a consistent run snapshot. Preserve original source identities and coverage declarations for subsequent production restoration.

Current Java entry points are `algorithm/TreeBuilder/AttributionWorkflow`, `ReadDataHandler`, `EdgeBuildHandler` and `CsrGraphHandler`. `AttributionWorkflow.trace` runs the existing chain. It currently accesses repositories through handlers; initially wrap it with `PeriodGraphProvider`, keeping warehouse adapters outside the reusable workflow. In-memory providers for algorithm acceptance must supply source records rather than precomputed CSR.

## Implementation process

1. Resolve sold product targets for each period using the existing loader semantics. Do not reinterpret currently ignored request fields as product/company filters.
2. Expand each target through date-scoped recorded inventory usage. Aggregate consumed quantities and amounts by inventory ID using exact decimal arithmetic, and retain material-to-receiving-product edges. Queue dependencies once and preserve the existing relationship semantics.
3. Retain the synthetic `__COGS__` node and sold-product-to-COGS edges under the existing contract. Its value comes from period sales valuation; it is not a production instance or an extra physical manufacturing step.
4. Build CSR using `CsrGraphHandler`, with aligned node, offset, successor and existing edge representations. Keep a stable mapping from graph positions to inventory/source identities. Where existing graph contracts do not carry original issue/coverage evidence, retain it in companion run data; do not claim it can be reconstructed from aggregated nodes.
5. Return distinct actual/comparable graph snapshots and their loading/coverage statuses. Do not mutate submitted arrays after publication. Validate shape, indices and unique inventory IDs before traversal; step 2 additionally requires a valid DAG.

## Output and hand-off

The discovery graph merges inventory IDs across records. It is not an occurrence tree and must not be passed directly to FGW. Supply graphs to branch discovery, and preserve original records, graph identities and coverage for [production-tree restoration](FGW_Build_Production_Trees.md). Selection dates for discovery do not authorise truncating complete selected production instances later.

## Missing evidence and execution

Report missing/noncomparable financial evidence explicitly, using exact decimal values rather than binary floating-point substitutions. Existing implementation details such as COGS null-to-zero handling or skipped self-consumption do not establish complete source evidence; retain diagnostics and do not declare an invalid discovery graph complete. Preserve existing public contracts; any necessary correction requires affected caller/document updates.

Initial CSR construction remains Java. A standalone CUDA TreeBuilder source does not establish backend linkage or change the parent's execution policy. No accelerator implementation or validation is established by this document.
