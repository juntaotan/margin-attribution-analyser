# Shared analysis fixture

Parent: [Main analysis design](Main_Analysis_Design.md). This file owns shared source data and expected tree shapes, not test procedures. Steps consume the relevant records through their real input boundaries. The [workflow test specification](Analysis_Workflow_Test_Case.md) owns the confirmed full-flow assertions; individual step test documents own their applicable checks.

## Two control-enclosure batches

Namespace: `FULL_FLOW_V1`; currency: `NZD`; cost scope: `manufacturing`. Comparable period: 1–30 August 2026; actual period: 1–30 September 2026. Place comparable records on 15 August and actual records on 15 September. Declare complete input/graph coverage for both periods and all referenced supplying instances. No returns, ambiguous identities or missing inputs are present in this positive fixture.

Each period sells ten units of A from its selected receiving batch. Provide sales quantity/cost records consistent with output costs 240/270 using the existing graph-loading contract. Receiving production IDs are `P0` and `P1`; explicit supplying IDs are `P0-S1`, `P0-S2`, `P1-S1`, `P1-S2`. Each instance produces ten units. Source shares on A's incoming S1/S2 occurrences are explicitly 1; no date, SKU or FIFO inference is permitted. Select `FULL_LINEAGE` for comparison.

```text
Comparable P0                  Actual P1
A                              A
├── S1                         ├── S1
│   ├── R1                     │   ├── R1
│   └── R2                     │   └── R2
└── S2                         └── S2
    ├── R3                         ├── R3
    └── R4                         └── R5
```

These are expected output trees, not prebuilt algorithm inputs. Recorded issues use material-to-receiver direction. Construct the preceding graph in step 1, retain its identities/coverage and pass the applicable original scope to tree construction. The tree's final reindexed CSR is a separate output.

## Material context evidence

| Code | Name | Specification | Supplier/source kind | Category | Unit | Comparable unit cost | Actual unit cost |
| --- | --- | --- | --- | --- | --- | ---: | ---: |
| A | Industrial control enclosure | 400 x 300 x 200 mm; assembled enclosure | Explicit internal production | Finished product | each | 24 | 27 |
| S1 | Steel enclosure assembly | Folded steel enclosure; 2 mm wall | Explicit internal production | Intermediate assembly | each | 13 | 15 |
| S2 | Surface finishing assembly | Coating package for one enclosure | Explicit internal production | Intermediate assembly | each | 9 | 10 |
| R1 | Cold-rolled steel sheet | SPCC; 2 mm thickness | Steel Supplier Alpha; purchased | Sheet metal | kg | 5 | 6 |
| R2 | Hexagon bolt | M6 x 20 mm; zinc plated; grade 8.8 | Fastener Supplier Beta; purchased | Fastener | each | 0.2 | 0.2 |
| R3 | Epoxy primer | Two-component epoxy; grey | Coating Supplier Gamma; purchased | Primer | kg | 10 | 10 |
| R4 | Standard topcoat | Polyester; grey RAL 7035 | Coating Supplier Gamma; purchased | Topcoat | kg | 6 | Not present |
| R5 | Corrosion-resistant topcoat | Polyurethane; grey RAL 7035; corrosion resistant | Coating Supplier Delta; purchased | Topcoat | kg | Not present | 8 |

Supply descriptions, source-kind/supplier references and exact unit-cost evidence scoped to each occurrence. Raw-material unit costs use actual issue amount divided by positive issue quantity; intermediate costs use confirmed supplying-instance total divided by its ten-unit output; root cost uses its complete production total divided by ten. All divisions here are exact. Retain the basis, currency, unit, cost scope and original issue/output/allocation references. Do not substitute text stating internal production for missing source-kind evidence.

Derive the remaining six embedding fields from final occurrence CSR: direct upstream/downstream codes, names and specifications. Expected upstream sets are A:{S1,S2}, S1:{R1,R2}, S2:{R3,R4} in P0 and {R3,R5} in P1; raw materials have empty upstream lists. Expected downstream sets are S1/S2:{A}, R1/R2:{S1}, R3/R4/R5:{S2}; A has an empty downstream list. Names/specifications must align with codes and occurrence IDs. These expectations validate extraction; do not inject them as precomputed context to bypass it.

## Original issue, output and processing evidence

Create six issue rows per period using distinct `(id,date)` identities and document references. The period-specific receiving/source production IDs follow the mapping above. R1–R5 are classified `RAW`; S1/S2 are `INTERMEDIATE` with the explicit supplying IDs and shares. Each row carries its material/product IDs, unit, quantity, amount, receiving production identity and provenance. Use exact decimals throughout.

| Receiving product/batch | Consumed material | Quantity | Comparable amount | Actual amount |
| --- | --- | ---: | ---: | ---: |
| S1 / period-specific S1 batch | R1 | 20 kg | 100 | 120 |
| S1 / period-specific S1 batch | R2 | 100 each | 20 | 20 |
| S2 / period-specific S2 batch | R3 | 5 kg | 50 | 50 |
| S2 / period-specific S2 batch | R4 in P0; R5 in P1 | 5 kg | 30 | 40 |
| A / P0 or P1 | S1 | 10 each | 130 | 150 |
| A / P0 or P1 | S2 | 10 each | 90 | 100 |

For every receiving instance, supply an identified processing pool and distinct allocations to its two incoming issue groups, including basis, issue identities and compatible currency/scope:

| Receiver | Labour pool | Overhead pool | Allocation to each incoming edge |
| --- | ---: | ---: | --- |
| S1 | 6 | 4 | Labour 3, overhead 2 |
| S2 | 6 | 4 | Labour 3, overhead 2 |
| A | 12 | 8 | Labour 6, overhead 4 |

Provide independently identified authoritative output receipts using `RECEIPTS` interpretation: ten units and complete costs S1:130/150, S2:90/100, A:240/270. Set comparable output-cost scope and matching currency/cost-scope evidence explicitly. Receivers sum incoming recorded valuations and local edge processing, never descendant totals again. Full source share 1 produces zero source-valuation differences on S1/S2 incoming occurrences.
