# Two-pass production-ID tree validation

Result: **PASS** for all 20 requirements in [the revised specification](../../algorithms/Build%20Product%20Production%20Trees%20by%20Production%20ID).

The final Java 21 backend invocation ran **151 tests: 0 failures, 0 errors, 0 skipped**, including 38 production-tree/scope/API tests and the unchanged Java FGW tests. The unchanged numerical fixture passed **157 independent CPU/POT checks** at FP64 tolerance `1e-8`; see [the numerical report](fgw-validation.md). These results establish the tested Java behaviour, not production readiness or GPU validation.

The active Docker context had no reachable daemon socket. The permitted host alternative used OpenJDK 21.0.11 and Maven Wrapper 3.9.16 on macOS arm64. Integration tests use isolated, rolled-back H2 PostgreSQL-mode data and real JPA queries with MockMvc. Initial sandbox socket restrictions affected two existing AI test-server tests; the final authorised run passed both without changing their assertions. The current invocation's log, rather than accumulated stale Surefire files, supplies the test count. Raw logs, `backend-summary.json`, `java.json` and `fgw-validation.json` are retained locally alongside this report and remain ignored by Git.

## Requirement checklist

Test names below refer to `ProductionTreeBuilderTest`, `ProductionScopeTraversalTest`, `ProductionTreeTwoPassTest` and `ProductionTreeIntegrationTest` under `backend/src/test/java/dev/margintrace/margin_attribution_backend/`.

| Requirement | Result | Automated evidence |
| --- | --- | --- |
| 1. Confirmed ID mapping, missing IDs and namespace identity | PASS | `databaseMappingFullPeriodRetrievalUnexpectedMaterialsAndRealEngine`, `identityFailuresAndCoverageAreExplicit`, `missingAmbiguousAndScopedIdentity`; decisions retain each scoped root instance. |
| 2. Several documents/dates per ID, separate IDs of one product | PASS | Original grouping and database/API tests retain P1/P2 separately and include January/February issues. |
| 3. Multiple products under one ID | PASS | `missingAmbiguousAndScopedIdentity`, `identityFailuresAndCoverageAreExplicit` return `AMBIGUOUS_PRODUCTION_PRODUCT`. |
| 4. Full-instance retrieval and incomplete coverage | PASS | Cross-period JPA retrieval, explicit import declarations, graph completeness and bounded graph coverage tests. |
| 5. Conservation, decimal precision and source-identity deduplication | PASS | `fixedGroupingConservationReorderingAndSplitting`, `decimalPrecisionAndMissingSubtotals`, `convergingPathsTerminalVisitsAndSharedSourceProjectionsDoNotDuplicate`; API fractional addition/projection checks. |
| 6. Output interpretations, conflicts and independent receipts | PASS | `outputConflictsSemanticsReceiptsAndCostScopes` preserves explicit receipt identities and independent quantity/cost interpretation. |
| 7. Compatible grouping, distinct positions and sources | PASS | `explicitPositionsAndDifferentSourcesRemainSeparate`, `sharedSourcesRemainOccurrenceSpecificAndKeepOriginalEvidence`, `distinctGraphPositionsWithEqualCodesRequireExplicitEdgeIdentity`. |
| 8. Unexpected actual materials and unreliable BOM | PASS | The JPA fixture inserts an unsupported planned-only BOM branch while retaining actual B/C issues; unverified BOM graphs are rejected and actual records missing from supplied scope remain excluded evidence. |
| 9. Explicit lineage and source shares without duplication | PASS | `explicitUpstreamSharesAndFinancialNonDuplication`, source-share API tests, shared-source tests and explicit unresolved-boundary tests; no FIFO or chronology-based batch selection. |
| 10. Missing inputs/units, discrepancies, returns, cycles and incomplete links | PASS | Existing missing-input/output/return/position tests, first-pass cycle tests and API partial material/processing evidence. |
| 11. CSR, embeddings, root/production/source alignment and empty FIFO references | PASS | `adapterAlignmentEligibilityVersionsAndQuantityInvariantObjective`, real-engine HTTP checks and consistent graph/node/issue permutation tests. |
| 12. Same product and matching topology scope; unchanged FGW fixture | PASS | Product/identity gates, `matchingTopologyScopeIsRequiredDespiteSameProductAndVectors`, Java numerical tests and 157 independent CPU/POT checks. No FGW engine, fixture or numerical-validator source was changed. |
| 13. Required first pass, direction, roots, branches, malformed CSR, cycles and coverage | PASS | All `ProductionScopeTraversalTest` cases, `suppliedScopeCannotBeReplacedByOutputGraphAndPeriodCoverageIsSeparate`, `apiRequiresInputScopeAndDoesNotAcceptFinalOutputCsrAsFallback`; missing input cannot fall back to generated final CSR. |
| 14. Terminal shared by allowed/excluded receivers | PASS | Fixed fixture retains L_RS/L_RB candidates and L_SC at the next level, with explicit exclusions and zero excluded contribution. |
| 15. Membership insufficient for cross-edges or incompatible batches | PASS | `bothEndpointMembershipDoesNotPermitCrossEdgeOrWrongBatch` rejects R-to-A without an allowed edge and rejects other receiving/supplying IDs with the same product codes. |
| 16. Dependency ordering, convergence, repeated queries/documents and per-root scope | PASS | Terminal-first query tests, converging/shared path projections, repeated source visits, multiple-root traversal and separate P1/P2 audit scopes. All documents and accepted source identities remain traceable. |
| 17. Node totals versus material flow/edge processing; zero, missing, partial and incompatible scopes | PASS | Fixed cost fixture, explicit zero/partial/incompatible processing tests, edge flow/processing completeness, API missing-labour and decimal/missing-material cases. Original currency/scope/provenance is retained. |
| 18. Explicit processing pool splits, unallocated evidence and duplicated totals | PASS | `poolSplittingCountedOnceUnsupportedAllocationAndOverdrawRejected` verifies 9/6 plus 3/2 allocations from a 12/8 pool; repeated allocation identity is deduplicated and two full copies fail validation. |
| 19. Embedded intermediate cost and separate valuation reconciliation | PASS | Fixed fixture, non-duplication tests and `upstreamProcessingIncompletenessDoesNotEraseKnownReceiverValuation`. Independent incoming-flow completeness prevents partial source breakdowns from invalidating a fully recorded downstream valuation. |
| 20. Both fixed fixtures and exact/partial costs through API/service | PASS | Original grouping expectations remain unchanged; `apiOriginalGroupingMissingCostsAndExplicitZeroAllocations`, `twoPassFixedCostFixtureRunsThroughJpaServiceAndHttp`, `apiMissingLaborKeepsKnownSubtotalAndExplicitZeroRemainsComplete`, `apiDecimalAdditionProjectionAndMissingMaterialStayExactAndPartial`. |

## Fixed fixture results

| Evidence | Expected | Actual |
| --- | ---: | ---: |
| P1 root material subtotal / output quantity | 300 / 20 | 300 / 20 |
| P2 root material subtotal / output quantity | 110 / 10 | 110 / 10 |
| B quantity / C quantity / material amount conservation | 150 / 7 / 410 | 150 / 7 / 410 |
| Full P_S source cost | 120 | 120 |
| Projected R quantity / material valuation | 2 / 20 | 2 / 20 |
| Projected edge labour / overhead | 2.4 / 1.6 | 2.4 / 1.6 |
| Projected S source total | 24 | 24 |
| Recorded S receiving valuation / source valuation difference | 30 / 6 | 30 / 6 |
| P_A material subtotal / labour / overhead / total | 30 / 4 / 6 / 40 | 30 / 4 / 6 / 40 |
| Recorded output minus complete root total | 0 | 0 |
| Preserved recorded output minus root material subtotal | 10 | 10 |
| Excluded L_RB / L_SC contribution | 0 / 0 | 0 / 0 |

Removing labour retains root material 30 and known subtotal 36 with a null complete total; explicit zero processing produces a complete total 30. Original source/allocation values remain separately available. The additional API decimal fixture adds 0.1 and 0.2 before a 0.2 projection: source cost 120.3, projected raw amount 20.06, projected total 24.06 and valuation difference 5.94. Missing source material retains its partial subtotal and leaves the complete source total unresolved. The independently complete root receiving valuation still contributes 30 once.

## Reproduction

Use the Docker Java 21 Maven command in [AGENTS.md](../../../AGENTS.md), or the intentionally installed Java 21 host alternative:

```sh
cd backend
./mvnw test --batch-mode --no-transfer-progress
```

From the repository root, with Java 21 and the existing pinned Python numerical oracle:

```sh
java -cp backend/target/classes:backend/target/test-classes \
  dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwFixtures \
  docs/validation/production-tree-two-pass-2026-10-11/java.json
python3 -m venv /tmp/production-tree-reference
/tmp/production-tree-reference/bin/pip install -r benchmark/fgw/requirements.txt
/tmp/production-tree-reference/bin/python benchmark/fgw/validate.py \
  --native-report docs/validation/production-tree-two-pass-2026-10-11/java.json \
  --output docs/validation/production-tree-two-pass-2026-10-11/fgw-validation.md
```

The validated oracle versions are Python 3.14.3, NumPy 2.5.3, SciPy 1.18.1 and POT 0.9.7.post1. The tests use independent tensor objectives, exact CPU LP comparison and POT; quantities and financial metadata do not enter the unchanged FGW objective.

## Remaining limitations

- PostgreSQL partition/migration behaviour, live warehouse coverage, deployed HTTP behaviour and production-scale latency/memory were not tested. No live warehouse writes or schema changes occurred.
- Graph actuality/coverage, classification, units, source batches/shares and processing allocations require explicit caller evidence; the current warehouse schema cannot independently prove these declarations or filter organisations by namespace.
- Only explicitly homogeneous source projections are supported. Unit conversions and return netting without a declared transaction rule remain unresolved. Missing evidence is reported, never imputed.
- CUDA/OpenCL device execution is unverified and unchanged; this revision does not require GPU implementation or optimisation. The numerical report explicitly marks CUDA coverage UNVERIFIED.
- Existing dense, synchronous Java FGW execution retains its scale limitations. Mathematical eligibility/convergence does not establish a manufacturing cause or complete total-cost restoration.
