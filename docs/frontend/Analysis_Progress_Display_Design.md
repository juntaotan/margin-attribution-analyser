# Analysis progress display design

Parent: [Main analysis design](../algorithms/Main_Analysis_Design.md). Protocol owner: [Analysis workflow implementation — Output and progress](../algorithms/Analysis_Workflow_Implementation.md#output-and-progress).

Status: **implemented** (2026-10-12) in `analysisProgress.ts`, `AnalysisProgressBar.tsx` and `AnalysisWorkflow`. The *Current behaviour and gaps* section records the behaviour before this change. This document governs how the Dual-BOM page reports the progress of one `/api/v1/analysis/run/stream` run. It does not change analysis results, financial values, thresholds or exports.

## Behaviour before this change

- `AnalysisWorkflow` emits `graph`, `branches`, `production-trees`, `embeddings`, one `comparison` per pair and `completed` (or `error`). Each event is sent **after** its stage finishes.
- The page shows one spinner and one label (`STAGES[stage]`) in the status strip under *Data interpretation*. The label shows the stage that has just finished, so during embedding preparation it reads *Production trees restored*.
- Warehouse snapshot loading happens in `AnalysisRunService` before the workflow starts, so it sends no event.
- The planned pair count first reaches the client in `completed`. Pair comparison is usually the longest stage, but its progress cannot be shown as *k of N*.
- Short-circuit outcomes (`NO_ABNORMAL_BRANCHES`, `COMPARISON_INPUT_REQUIRED`, `EXECUTION_UNAVAILABLE`, `CANCELLED`) do not show which steps were skipped.

## Steps shown

| # | Step label | Running from | Finished by | Summary shown when finished (from event data only) |
|---|---|---|---|---|
| 1 | Load warehouse records | request sent | first event received | — |
| 2 | Build period graphs | `stage` graph | `graph` | actual / comparable node counts, diagnostic count |
| 3 | Discover abnormal branches | `stage` branches | `branches` | path count |
| 4 | Restore production trees | `stage` production-trees | `production-trees` | tree count |
| 5 | Prepare embeddings | `stage` embeddings | `embeddings` | generated · unresolved |
| 6 | Compare production pairs | `stage` comparison (`total` = planned) | last `comparison`, or `completed` | executed · rejected · failed of planned |

`completed` closes the run. Its `status` sets the terminal state of every step that has not finished:

| Final status | Steps not reached |
|---|---|
| `NO_ABNORMAL_BRANCHES` | 4–6 *Skipped: no abnormal branches* |
| `COMPARISON_INPUT_REQUIRED` (before trees) | 4–6 *Skipped: comparison input required* |
| `EXECUTION_UNAVAILABLE` | current step *Unavailable*, later steps *Skipped* |
| `CANCELLED` | current step *Cancelled*, later steps *Skipped* |
| `INSUFFICIENT_PRODUCTION_INSTANCES` | 6 *Skipped: no comparable pairs* |
| `error` event or broken stream | current step *Failed* with the message; later steps stay *Pending* |

Step states are `pending`, `running`, `done`, `skipped`, `failed`. A step marked `done` can still carry a warning (for example `unresolved > 0` embeddings, or rejected/failed pairs) as an amber marker. A warning is not a failure. The display never derives counts, statuses or skip reasons the backend did not report.

## Protocol change (additive)

Add one event type, `stage`, which the workflow emits immediately before each stage starts:

```json
{"type":"stage","analysisId":"…","sequence":3,"data":{"stage":"comparison","total":12}}
```

- `stage` is one of `graph`, `branches`, `production-trees`, `embeddings`, `comparison`, which match the existing completion event names.
- `total` is present only when the workflow already knows it. In phase 1 that means the planned pair count for `comparison`, computed by `ProductionPairSelector` before the loop.
- Events keep the shared run ID and increasing sequence. No stage is re-run, and no evidence is duplicated in `stage` events.
- Completion events are unchanged, so clients that ignore unknown types keep working. `/reconcile-periods/stream` is unaffected.

Without a backend change, the frontend can only infer that the next step is running once the previous one finishes, and step 6 would have to show an indeterminate bar. That would show fewer details but remains a fallback if the protocol change is rejected.

**Phase 2 (only if embedding preparation proves slow in practice):** `EmbeddingPreparation` already submits inference in batches of 16. It could emit `{"type":"stage-progress","data":{"stage":"embeddings","completed":32,"total":80}}` after each batch. That requires passing a listener into `prepare`, which is why it is deferred.

## Placement

```text
┌ AnalysisHeader ──────────────────────────────────────────────────────────────┐
├ ScopeControlsBar  [periods] [thresholds]                     [▶ Run analysis] ┤
├ Data interpretation ▸                                                         ┤
├ PROGRESS BAR (replaces the current status strip) ─────────────────────────────┤
│ ✓ Load 0.4s ─ ✓ Graphs 1.1s ─ ✓ Branches 0.2s ─ ✓ Trees 2.3s ─ ✓ Embeddings ─ │
│   ◐ Compare pairs  7 / 12  ▓▓▓▓▓▓▓░░░░  6 executed · 1 rejected             │
├───────────────────────────────────────────────────┬──────────────────────────┤
│ Dual-BOM canvas (appears once branches arrive)    │ Audit sidebar            │
└───────────────────────────────────────────────────┴──────────────────────────┘
```

1. **Primary position:** a full-width progress bar that replaces the existing `role="status"` strip, directly below the controls and above the canvas.
   - It sits next to the *Run analysis* button that starts the run.
   - It stays visible while the canvas renders. The canvas appears after step 3, while steps 4–6 are still running, so progress cannot live inside the canvas placeholder.
   - It uses the space the status strip already takes, so the canvas does not lose height.
2. **Desktop (≥ `lg`):** a horizontal stepper with six nodes. Each node has an icon (pending ○, running spinner, done ✓, skipped –, failed ✕), a short label and, once finished, its elapsed time. The running step expands to show its live summary. Step 6 shows a determinate bar `k / N` with live executed/rejected/failed counts from `comparison` dispositions.
3. **Narrow screens:** one line, `Step 4 of 6 · Restoring production trees`, with a thin overall bar. Tapping it opens the full list vertically.
4. **After completion:** the bar collapses to one summary line: final status, `paths · planned · executed · rejected · failed` and total time. The execution backend is not repeated here because `AnalysisHeader` already shows each stage's `execution` report. The existing *Inputs changed…* warning stays on this line. A disclosure re-opens the per-step view with timings.
5. **Canvas placeholder before step 3:** replaces *Preparing recorded analysis evidence…* with the label of the running step, so the centre of the page shows where the run is.
6. **Navigation badge:** `onStatusChange` sends `Running · 4/6` instead of `Running`, and the badge does not wrap. The page stays mounted when the user navigates away, so the header badge shows progress from other pages.

Elapsed times come from the browser clock at event arrival and include network and serialisation delay. A tooltip labels them as browser measurements, not backend timings.

## Accessibility

- The container keeps `role="status"` and `aria-live="polite"`. It announces step transitions and the final status only, not every pair.
- Step 6's bar is a `role="progressbar"` with `aria-valuenow`/`aria-valuemax`.
- States are shown by icon and text, never by colour alone. Use the existing palette: `#466987` running, `#866322` warning, `#A64242` failed.

## Implementation plan

Backend:

- `algorithm/workflow/AnalysisWorkflow.java`: emit `stage` before `graphs.load` (graph), before `reconcile` (branches), before `productions.load`, before `preparation.prepare`, and after `ProductionPairSelector.select` with `total = planned`.
- `AnalysisWorkflowTest`: update the event-order assertion to include the `stage` events and their `stage`/`total` data, keeping the sequence and ID checks.
- `docs/algorithms/Analysis_Workflow_Implementation.md`: add `stage` to the event list in *Output and progress*.

Frontend:

- `frontend/src/analysisProgress.ts`: a pure `reduceProgress(state, event, now)` and `initialProgress(now)`, covering step states, counts, skip reasons and timings. It has no React code, so it can be tested.
- `frontend/src/analysisProgress.test.ts` (`node:test`, like `analysisRun.test.ts`) covers:
  - the full run;
  - `NO_ABNORMAL_BRANCHES` and `COMPARISON_INPUT_REQUIRED` short-circuits;
  - `error` during comparison;
  - a stream ending without `completed`;
  - mixed pair dispositions;
  - an unknown event type being ignored.
- `frontend/src/components/DualBom/AnalysisProgressBar.tsx`: the presentation component.
- `DualBomAnalysisPage.tsx`: replace the `stage` state and `STAGES` map with the reducer, and render `AnalysisProgressBar` in place of the status strip. Preserve the existing uncommitted edits in this file.
- `App.tsx`: keeps the status badge on one line for the longer `onStatusChange` string.

Validation: backend `./mvnw test` (Docker), the reducer tests with Node (`node --experimental-strip-types frontend/src/analysisProgress.test.ts`), and the Docker frontend build. A mocked-stream browser check covering a full run, a short circuit and an error is recommended because the build is not a behavioural test.
