# Dual BOM graph display design

Parent: [Main analysis design](../algorithms/Main_Analysis_Design.md). This specification governs presentation of recorded CSR graphs, period records and supplied abnormal-branch evidence. Numerical analysis, financial records, backend thresholds and exports remain unchanged.

## Orientation and columns

Read every tree from left to right: **Cost of Goods Sold -> sold products -> intermediate items -> materials**. CSR edges run material-to-receiving item; presentation reverses them only for display.

Columns are fixed by display depth: column 0 is COGS, column 1 the products, and deeper columns follow each displayed level. Real material terminals (items with no recorded consumed material in either period) align in the last column, labelled *Material terminals*. Fold controls occupy the same column slots as business nodes, centred within the slot, so columns never drift.

Rows use a tidy-tree order. Children are ranked by abnormal evidence (largest supplied `endingCostDifference` on or below the child), then by absolute issue-amount difference within the drawn parent, then by inventory ID. Leaves take consecutive rows; a parent sits midway between its first and last child. The largest differences therefore appear at the top.

## Data used

| Quantity | Source | Definition |
|---|---|---|
| Product ΔCOGS | `records.sales` | Sum of `cost` by `productId` in each period, actual minus comparable |
| Issue difference in a parent | `records.issues` | Sum of `amount` by (`productId`, `materialId`), actual minus comparable |
| Node difference | CSR node `cost` | Actual minus comparable; unavailable if absent or null in either period |
| Abnormal evidence | `branches` | Every position on a supplied branch is abnormal; its last non-COGS position carries the boundary difference |

Amounts are exact signed micro-units (six decimal places, plain or exponent notation). A null amount makes its sum unavailable; it is never treated as zero. A product absent from one period's sales contributes zero in that period because no sale was recorded.

## Initial view

1. **Coverage rule.** Rank products by exact |ΔCOGS|, breaking ties by inventory ID. Select the shortest prefix whose cumulative |ΔCOGS| reaches the chosen share (default **50 %**; the selector also offers 20, 40, 60, 80 and 100 %) of Σ|ΔCOGS|. The product that crosses the share is included. Products with zero or unavailable ΔCOGS are never selected; the toolbar reports unavailable products. At most **15** products are selected.
2. **Remainder boxes.** Exactly two boxes attach to COGS: *Abnormal products* (unselected products with abnormal evidence below them) and *Normal products* (all other unselected products). Clicking a box reveals the next 20 members in the product column, ordered as above.
3. **Compression below a product.** For every displayed node:
   - abnormal children are displayed directly, so a supplied branch appears complete;
   - normal children leading to deeper abnormal nodes collapse into one `…` bridge whose display children are the nearest abnormal nodes;
   - normal children without abnormal evidence below them collapse into one `+n` remainder showing the count and summed issue difference.

   This gives `COGS -> product -> … -> abnormal branch -> +n`, and inside a folded child `… -> abnormal -> … -> abnormal -> +n`. At most five abnormal routes are shown directly beneath one node; extra routes join the remainder, which is then marked abnormal.

For `A-B-C-D-E-F` and `A-B-C-G-H-I`, with branch `F-E-D-C`, the initial tree is:

```text
COGS -> A -> […] -> C -> D -> E -> F
                     -> [+1]
     -> [Abnormal products]
     -> [Normal products]
```

A bridge never claims a direct recorded relationship: nodes behind it show no parent issue difference.

## Expansion

Expansion happens in place; nothing else in the tree moves out of view or changes scope.

- Opening a bridge reveals the whole hidden normal span down to the next abnormal nodes. The remainders of those intermediate nodes stay folded.
- Opening a remainder reveals 20 members per click; the control remains as `+n` until every member is shown.
- *Undo expansion* restores the previous state; *Collapse all* returns to the initial view. Changing coverage also returns to the initial view.
- Clicking a business node only selects it for evidence. Selecting a product enables *Production evidence*.

Each projection shows at most **150** business nodes. Beyond that, further content stays inside folds and the toolbar reports that the display budget was reached.

## Two periods

Both panes render the same layout, built from the union of both periods' relationships keyed by inventory ID. A node therefore occupies the same row and column in both panes. Each pane shows its own recorded quantity and cost:

- a node absent in that period shows *Not recorded in this period*;
- a relationship drawn from the other period shows *Relationship to parent not recorded* and a faint dashed edge.

Expanding A-to-C where actual records A-B-C and comparable records A-X-C shows B and X in separate rows of both panes, each marked unrecorded in the other period. Both panes share one viewport, so panning and zooming stay synchronised. Comparable anomaly styling indicates correspondence with actual-side evidence, not a separately calculated comparable anomaly.

Missing COGS, abnormal paths without a recorded route to COGS, unparseable amounts and cycles in the merged graph are reported explicitly. No values or relationships are inferred.

## Responsiveness

A Web Worker indexes both full graphs and their records, and returns only the bounded display tree with precomputed columns and rows. React Flow renders the given positions; no general graph layout runs in the browser thread. Stale worker replies are ignored by sequence, and replaced or unmounted workers are terminated. Full parsing, cloning and indices still use memory proportional to the input.

## Validation checklist

1. Exact decimal parsing (including exponent notation and excess-precision rejection), signed formatting and stable ranking.
2. Coverage prefix at 50/60/61/80/100 %, unavailable and zero ΔCOGS, ties, and exactly two remainder boxes.
3. The A–I fixture: complete abnormal branch, bridge before C, `+1` remainder, terminal in the last column.
4. In-place bridge and remainder expansion; folded anomalies inside folded children (`… -> abnormal -> … -> abnormal -> …`).
5. Shared layout across periods with different intermediate nodes, shuffled positions, null costs and unrecorded relationships.
6. Wide (1,000-child) and deep (2,000-node) graphs: ranking, paging, budget and a single bridge.
7. Missing COGS, unlinked anomalies, shared materials and merged-graph cycles.
8. Large-model timing with bounded output; isolated browser checks for columns, row alignment, expansion, shared viewport, coverage, resident navigation and a 25,002-node fixture.
9. Frontend Docker build, document references and whitespace checks.

## Reproducible checks

`frontend/src/graphDisplay.ts` owns indexing, coverage, compression and layout; `graphDisplay.worker.ts` owns worker requests; `DualBomTreeCanvas.tsx` renders the panes and handles expansion.

From the repository root, with Node 22.6 or later (or the `node:22` image):

```sh
node --experimental-strip-types frontend/src/graphDisplay.test.ts
docker build --target build -t margin-frontend-check -f frontend/Dockerfile frontend
```

For browser checks, start Vite from `frontend/` on loopback port 5173 (`npx vite --host 127.0.0.1 --port 5173`), then run the script with an available Playwright runtime and installed Chrome. `PLAYWRIGHT_MODULE` may point to an external runtime's ESM entry (`index.mjs`), `PLAYWRIGHT_CHANNEL` selects another installed channel, `MARGINTRACE_FRONTEND_URL` overrides the URL, and `DISPLAY_SCREENSHOT` optionally saves screenshots.

```sh
node frontend/tests/graph-display.browser.mjs
```

All fixture API traffic is mocked; no warehouse data is accessed.
