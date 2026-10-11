# Embedding preparation tests

Implementation: [embedding preparation](Embedding_Implementation.md). Use the [shared material/tree fixture](Analysis_Fixture.md) for ordinary preparation tests. Exercise real context extraction, canonicalisation, resolution and CSR alignment through explicitly passed inputs.

## General automated cases

| ID | Input or variation | Required assertions |
| --- | --- | --- |
| E01 | Complete root/intermediate/raw-leaf descriptions and evidence | All twelve fields reach the versioned template; each occurrence retains identity, source references and cost basis. Root supplier/source kind is its own evidence. |
| E02 | Final material-to-receiver graph | Correct incoming/outgoing neighbour codes/names/specifications, aligned tuples and valid empty root/leaf lists. |
| E03 | Same code with different supplier, cost or neighbours; permuted input lists | Different semantic contexts remain distinct. Equivalent canonical ordering yields equal content hashes; occurrence/order IDs stay provenance rather than extra semantic text. |
| E04 | Missing/conflicting descriptions, supplier/source-kind, unit cost or provenance | Explicit missing/conflict/unverified status; no fabricated supplier, zero cost or accepted placeholder vector. |
| E05 | Duplicate complete canonical inputs, supplied vectors and cache hit/miss/invalidation | Infer each unresolved unique input once; preserve all occurrence mappings. Keys include namespace, context/model/template/normalisation/precision. |
| E06 | Provider returns batches in a different order | Match by input identity, then align exactly one finite 384-component vector to each resolved final CSR occurrence. |
| E07 | Wrong dimension, NaN/Inf, unsupported zero-norm outputs or incompatible versions | Explicit rejection/diagnostic; no padding, truncation or zero substitution. |
| E08 | Overlength context, provider failure/timeout/cancellation or partial batch | Explicit statuses and partial evidence; no silent text truncation or false complete outcome. |
| E09 | Multiple pairs and a no-abnormal-path run | Reuse prepared vectors across pairs; no-path short-circuit performs no inference. |
| E10 | Requested/actual inference backend and allowed fallback | Record model/revision/runtime/device/precision and actual fallback; do not claim remote device identity without reported evidence. |

A recording deterministic provider may be used for ordinary orchestration tests to inspect templates, simulate response ordering/errors and verify calls. It does not replace the real model required for full-flow acceptance.

## Real-model smoke check

With an explicitly configured compatible model/runtime, encode the complete shared contexts, including roots and intermediates. Assert finite 384-dimensional outputs and correct mappings/provenance; record supplied/cache/generated counts and timing. Do not assume vectors from CPU/GPU or repeated inference are bitwise identical. Use declared model-specific tolerances for paths in scope.

The ordinary smoke check does not establish semantic model quality or prescribe a fixed FGW score. The existing Main model-quality/full-flow requirements remain applicable to those tasks; calibrated thresholds must be confirmed separately rather than invented in this document. If model access is unavailable, report the exact prerequisite and leave the real-model check unverified.

## Execution and report

Implement automated Java preparation tests and run the prescribed Java 21 Maven test command. Report E01–E10 and the configured real-model smoke check separately, with actual calls, dimensions, statuses, provenance and PASS/FAIL/unverified outcomes. This documentation change executes no model or automated tests.
