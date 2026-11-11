# Dual BOM display design

Parent: [Main analysis design](../algorithms/Main_Analysis_Design.md). This specification governs presentation of recorded CSR graphs, period records and supplied abnormal-branch evidence. Numerical analysis, financial records, backend thresholds and exports remain unchanged.

The main view is a **double table**: one row per display item, with the comparable period, the actual period and their difference side by side. A product's **tree graph** opens when the product is clicked.

## Tree order

Every tree reads **Cost of Goods Sold -> sold products -> intermediate items -> materials**. CSR edges run material-to-receiving item; presentation reverses them only for display.

Children are ranked by abnormal evidence (largest supplied `endingCostDifference` on or below the child), then by absolute issue-amount difference within the drawn parent, then by inventory ID, so the largest differences appear first. Projected items are returned in tree order: each parent precedes its children, and each item carries its display depth (COGS 0, products 1). A product-group row follows the products it has revealed.

## Double table

The table renders projected items in tree order, indented by depth, in one grid so that both periods always share a row:

| Item | Comparable: quantity · total cost · unit cost | Actual: quantity · total cost · unit cost | Difference: total · unit |
|---|---|---|---|

- **Period values.** COGS and intermediate items show their recorded CSR quantity and cost. Products show the sums of their sales quantity and cost in that period, the basis of ΔCOGS; a product without sales in a period shows zero, and a null sale makes the sum unavailable. The COGS quantity is synthetic and shown as `—`.
- **Unit cost** is total cost ÷ quantity, computed exactly in micro-units and rounded half away from zero to six decimal places. It is unavailable when either value is unavailable, the quantity is zero, or the row is COGS.
- **Total difference** is the item's Δ (defined under *Data used*), with the issued-amount difference within the drawn parent beneath it. **Unit difference** is actual unit cost minus comparable unit cost, from the rounded values; it is unavailable when either unit cost is unavailable.
- A node absent from a period spans that period's three cells with *Not recorded in this period*; a relationship recorded only in the other period shows *Not linked*.
- Increases are shown as unfavourable and decreases as favourable; unavailable values are `—` and never zero.

## Data used

| Quantity | Source | Definition |
|---|---|---|
| Product ΔCOGS | `records.sales` | Sum of `cost` by `productId` in each period, actual minus comparable |
| Product period values | `records.sales` | Sums of `quantity` and `cost` by `productId` in each period |
| Issue difference in a parent | `records.issues` | Sum of `amount` by (`productId`, `materialId`), actual minus comparable |
| Node difference | CSR node `cost` | Actual minus comparable; unavailable if absent or null in either period |
| Abnormal evidence | `branches` | Every position on a supplied branch is abnormal; its last non-COGS position carries the boundary difference |

Amounts are exact signed micro-units (six decimal places, plain or exponent notation). A null amount makes its sum unavailable; it is never treated as zero. A product absent from one period's sales contributes zero in that period because no sale was recorded.

## Initial view

1. **Products closed.** Every product row, including products revealed from a group, starts with its children hidden. Opening a product (its chevron or the right-arrow key) shows the compressed children defined in step 4; closing it (chevron or left-arrow key) removes them together with every expansion beneath that product.
2. **Coverage rule.** Rank products by exact |ΔCOGS|, breaking ties by inventory ID. Select the shortest prefix whose cumulative |ΔCOGS| reaches the chosen share (default **50 %**; the selector also offers 20, 40, 60, 80 and 100 %) of Σ|ΔCOGS|. The product that crosses the share is included. Products with zero or unavailable ΔCOGS are never selected; the toolbar reports unavailable products. At most **15** products are selected.
3. **Remainder boxes.** Exactly two group rows attach to COGS: *Abnormal products* (unselected products with abnormal evidence below them) and *Normal products* (all other unselected products). Clicking a group reveals the next 20 members as product rows, ordered as above.
4. **Compression below an opened product.** For every displayed node:
   - abnormal children are displayed directly, so a supplied branch appears complete;
   - normal children leading to deeper abnormal nodes collapse into one `…` bridge whose display children are the nearest abnormal nodes;
   - normal children without abnormal evidence below them collapse into one `+n` remainder showing the count and summed issue difference.

   This gives `COGS -> product -> … -> abnormal branch -> +n`, and inside a folded child `… -> abnormal -> … -> abnormal -> +n`. At most five abnormal routes are shown directly beneath one node; extra routes join the remainder, which is then marked abnormal.

For `A-B-C-D-E-F` and `A-B-C-G-H-I`, with branch `F-E-D-C`, the initial table and the table after opening A are:

```text
Cost of Goods Sold                Cost of Goods Sold
  ▸ A                               ▾ A
  Abnormal products (0)                 …
  Normal products (1)                     C
                                            D
                                              E
                                                F
                                            +1
                                    Abnormal products (0)
                                    Normal products (1)
```

A bridge never claims a direct recorded relationship: nodes behind it show no parent issue difference.

## Expansion

Expansion happens in place; nothing else in the tree moves out of view or changes scope.

- Opening a bridge reveals the whole hidden normal span down to the next abnormal nodes. The remainders of those intermediate nodes stay folded.
- Opening a remainder reveals 20 members per click; the control remains as `+n` until every member is shown.
- *Undo expansion* restores the previous state; *Collapse all* returns to the initial view. Changing coverage also returns to the initial view.
- Clicking a product row opens its tree graph. Clicking any other business row selects it for the evidence inspector.

Each projection shows at most **150** business nodes. Beyond that, further content stays inside folds and the toolbar reports that the display budget was reached.

## Two periods

The projection is built from the union of both periods' relationships keyed by inventory ID, so an item has one row covering both periods. Expanding A-to-C where actual records A-B-C and comparable records A-X-C shows B and X as separate rows, each marked unrecorded in the other period. Comparable anomaly styling indicates correspondence with actual-side evidence, not a separately calculated comparable anomaly.

## Product tree view

Clicking a product row opens a modal with the comparable and actual tree graphs of that product only. The projection is rooted at the product, its children are open, and the same compression, paging and budget apply; coverage and product groups do not.

- Columns are fixed by depth from the product column; real material terminals (items with no recorded consumed material in either period) align in the last column, labelled *Material terminals*. Fold controls occupy the same column slots as business nodes, centred within the slot.
- Rows use a tidy-tree layout: leaves take consecutive rows and a parent sits midway between its first and last child.
- Both panes render the same layout and share one viewport. Each pane shows its own values; a node absent in that period shows *Not recorded in this period*, and a relationship drawn from the other period shows *Relationship to parent not recorded* and a faint dashed edge.
- Bridges and remainders expand in place, with *Undo expansion* and *Collapse all*. *Production evidence* closes the tree and opens the production-tree comparison for the product.

Missing COGS, abnormal paths without a recorded route to COGS, unparseable amounts and cycles in the merged graph are reported explicitly. No values or relationships are inferred.

## Responsiveness

One Web Worker per analysis indexes both full graphs and their records; the table and the product tree send queries to it and receive only bounded projections with precomputed depth, columns and rows. React Flow renders the given positions; no general graph layout runs in the browser thread. Replies are routed by sequence, replies to superseded queries are ignored, and replaced or unmounted workers are terminated. Full parsing, cloning and indices still use memory proportional to the input.

## Validation checklist

1. Exact decimal parsing (including exponent notation and excess-precision rejection), signed formatting and stable ranking.
2. Coverage prefix at 50/60/61/80/100 %, unavailable and zero ΔCOGS, ties, and exactly two remainder boxes.
3. Products closed by default (including products revealed from a group), tree order and depth, and the A–I fixture after opening A: complete abnormal branch, bridge before C, `+1` remainder, terminal in the last column.
   - Single-product projection: rooted at the product with the same rows as the opened product; unknown products are rejected.
   - Unit cost: exact division and half-away-from-zero rounding, zero quantity and null cost, sales basis for products, none for COGS.
4. In-place bridge and remainder expansion; folded anomalies inside folded children (`… -> abnormal -> … -> abnormal -> …`).
5. Shared layout across periods with different intermediate nodes, shuffled positions, null costs and unrecorded relationships.
6. Wide (1,000-child) and deep (2,000-node) graphs: ranking, paging, budget and a single bridge.
7. Missing COGS, unlinked anomalies, shared materials and merged-graph cycles.
8. Large-model timing with bounded output; isolated browser checks for the closed default view, column groups, total and unit differences, opening and closing products, in-place expansion, period-only intermediates, the product tree graph (alignment, columns, expansion), coverage, resident navigation and a 25,002-node fixture.
9. Frontend Docker build, document references and whitespace checks.

## Reproducible checks

`frontend/src/graphDisplay.ts` owns indexing, coverage, compression, unit costs and layout; `graphDisplay.worker.ts` answers worker queries; `components/DualBom/useGraphProjection.ts` shares one worker per analysis and holds each projection's query history; `DualBomTreeTable.tsx` renders the double table and `ProductTreeModal.tsx` the product tree graphs.

From the repository root, with Node 22.6 or later (or the `node:22` image):

```sh
node --experimental-strip-types frontend/src/graphDisplay.test.ts
docker build --target build -t margin-frontend-check -f frontend/Dockerfile frontend
```

For browser checks, start Vite from `frontend/` on loopback port 5173 (`npx vite --host 127.0.0.1 --port 5173`), then run the script with an available Playwright runtime and installed Chrome. `PLAYWRIGHT_MODULE` may point to an external runtime's ESM entry (`index.mjs`), `PLAYWRIGHT_CHANNEL` selects another installed channel (for example `chromium` in the Playwright image), `MARGINTRACE_FRONTEND_URL` overrides the URL, and `DISPLAY_SCREENSHOT` optionally saves screenshots. One containerised route, from `frontend/`:

```sh
docker run --rm --ipc=host -v "$PWD:/w" -w /w mcr.microsoft.com/playwright:v1.49.1-noble bash -c '
  mkdir -p /tmp/pw && (cd /tmp/pw && npm init -y >/dev/null && npm i playwright@1.49.1 >/dev/null)
  npx vite --host 127.0.0.1 --port 5173 >/tmp/vite.log 2>&1 &
  for i in $(seq 1 60); do curl -s -o /dev/null http://127.0.0.1:5173 && break; sleep 1; done
  PLAYWRIGHT_MODULE=/tmp/pw/node_modules/playwright/index.mjs PLAYWRIGHT_CHANNEL=chromium node tests/graph-display.browser.mjs'
```

This uses the `node_modules` already installed in `frontend/` (run `npm ci` first if absent).

```sh
node frontend/tests/graph-display.browser.mjs
```

All fixture API traffic is mocked; no warehouse data is accessed.
