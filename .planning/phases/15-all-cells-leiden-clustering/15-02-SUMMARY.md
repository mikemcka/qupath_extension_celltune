---
phase: 15-all-cells-leiden-clustering
plan: 02
subsystem: ml
tags: [leiden, hnsw, ann, recall-gate, java, jelmerk]

# Dependency graph
requires:
  - phase: 15-01
    provides: "HnswKnnIndex ANN wrapper (static knn() + live build()/queryRow()/setEf() instance API)"
provides:
  - "AnnRecallException: unchecked exception for the recall-gate abort (no labels written)"
  - "LeidenModel.gateAnnRecall + recallSampleSize/exactNeighborsForQueries/meanRecall: capped-subsample recall gate with ef-escalation, reusable by any future ANN-routed clustering path"
  - "LeidenModel.clusterViaAnn + annNeighborsWithGate: single-image (and cohort-graph-ready) HNSW-routed Leiden, agreeing with exact Leiden by ARI, feeding the unchanged Jaccard/SNN + association-strength-normalized CWTS pipeline"
affects: [15-03-primitive-snn-rewrite, 15-04-two-pass-cohort-driver, 15-05-ui-docs]

# Tech tracking
tech-stack:
  added: []
  patterns: ["Recall gate: capped ~0.1%/10k-row subsample vs bounded-heap exact reference, geometric ef escalation on a single live (not rebuilt) index, hard abort via a dedicated unchecked exception on persistent under-recall", "Package-private test seams (gateAnnRecall, annNeighborsWithGate) so ANN-specific behavior (hidden per D-09) stays testable via stub neighbour sources rather than a user-facing knob"]

key-files:
  created:
    - src/main/java/qupath/ext/celltune/model/AnnRecallException.java
  modified:
    - src/main/java/qupath/ext/celltune/model/LeidenModel.java
    - src/test/java/qupath/ext/celltune/model/LeidenModelTest.java

key-decisions:
  - "Recall gate implemented as a caller-supplied BiFunction<Integer,int[],int[][]> (ef, sampleIdx) -> neighbours, rather than hard-wiring it to HnswKnnIndex. This decouples the gate's logic (samplng, exact reference, escalation loop, abort) from the ANN implementation, making it directly unit-testable with a stub (Task 1) and reused unchanged by clusterViaAnn's real-index wiring (Task 2)."
  - "clusterViaAnn's degraded-ANN-params abort test uses the same stubbed-wiring approach as Task 1's recall-gate tests, not a real crippled HnswKnnIndex -- D-09 deliberately hides ALL ANN knobs from callers, so there is no user-facing way to make the real index recall-fail; a stub neighbour source is the only way to exercise the abort path deterministically, and it exercises the identical gateAnnRecall code path clusterViaAnn calls uncaught."
  - "exactNeighborsForQueries reuses the existing private nearestForRow bounded max-heap selection (only invoked for the sampled indices, not every row) rather than writing new selection code -- keeps the recall-gate's exact reference at O(sample x n), never featureKnn's O(n^2) (Pitfall 3 / threat T-15-04)."

requirements-completed: []  # LEI-07/LEI-10 span this plan and Plans 03-05 (cohort-scope ANN routing, primitive-array SNN, UI/docs); not marking complete until satisfied across all contributing plans, per Plan 01's precedent.

# Metrics
duration: 20min
completed: 2026-07-06
---

# Phase 15 Plan 02: clusterViaAnn + Recall Gate Summary

**Routed Leiden's single-image kNN graph build through the Plan 01 HnswKnnIndex behind a runtime recall gate (capped-subsample vs exact, geometric ef escalation, hard abort via AnnRecallException on persistent under-recall), leaving the downstream Jaccard/SNN + CWTS Leiden pipeline untouched.**

## Performance

- **Duration:** ~20 min (git-log commit-to-commit span; excludes the upfront read-and-plan-review phase)
- **Started:** 2026-07-06T02:41:00Z (approx, following 15-01's completion commit)
- **Completed:** 2026-07-06T02:49:43Z
- **Tasks:** 2 completed
- **Files modified:** 3 (1 created, 2 modified)

## Accomplishments
- `AnnRecallException` (unchecked): the phase's correctness gate (D-08) -- thrown only when ANN recall stays below 0.95 after ef-escalation, guaranteeing no `Cluster` labels are ever written for an under-recall run.
- `LeidenModel.gateAnnRecall`: samples `min(10_000, max(1, round(n*0.001)))` query rows (D-07), computes their EXACT neighbours via a new `exactNeighborsForQueries` helper that reuses the existing bounded max-heap `nearestForRow` selection (only for the sampled indices, never all-pairs `featureKnn` -- keeps cost O(sample x n), Pitfall 3 / threat T-15-04), then repeatedly calls a caller-supplied `(ef, sampleIdx) -> int[][]` query function starting at `ef=64`, doubling geometrically up to 4 times (hidden defaults, D-09) whenever `meanRecall` is below 0.95, returning the passing recall or throwing `AnnRecallException` with the measured value in its message.
- `LeidenModel.clusterViaAnn`: same signature shape as `cluster` (plus a `reproducible` flag), builds a live `HnswKnnIndex`, runs it through `gateAnnRecall` (escalating the SAME built index's `ef` via `setEf`, never rebuilding -- D-08), and on pass queries the full graph once at the passing `ef`, feeding the identical unchanged `buildJaccardWeightedNetwork` -> `createNormalizedNetworkUsingAssociationStrength()` -> `LeidenAlgorithm.findClustering` pipeline `cluster` already uses. `n==0`/`n==1` degenerate cases handled identically to `cluster`.
- HNSW-routed Leiden labels agree with exact-featureKnn Leiden by Adjusted Rand Index (new `adjustedRandIndex` test helper, standard Hubert & Arabie contingency-table formula) on a synthetic 4-blob cloud, `>= 0.85`.
- `transferLabels`, `featureKnn`, and `cluster` left byte-for-byte unchanged; full `./gradlew test` suite green (Phase 14 tests unaffected) and `./gradlew shadowJar` succeeds.

## Task Commits

1. **Task 1: AnnRecallException + recall-gate helper** - `51adcea` (feat, tdd -- see TDD Gate Compliance note below)
2. **Task 2: clusterViaAnn -- route Leiden through HNSW + recall gate** - `6882db9` (feat, tdd -- see TDD Gate Compliance note below)

**Plan metadata:** (this commit, pending)

## Files Created/Modified
- `src/main/java/qupath/ext/celltune/model/AnnRecallException.java` - New unchecked exception, single `String message` constructor
- `src/main/java/qupath/ext/celltune/model/LeidenModel.java` - Added the recall-gate section (`recallSampleSize`, `exactNeighborsForQueries`, `meanRecall`, `gateAnnRecall`) and `clusterViaAnn`/`annNeighborsWithGate`/`queryExcludingSelf`; `featureKnn`, `cluster`, `transferLabels` unchanged
- `src/test/java/qupath/ext/celltune/model/LeidenModelTest.java` - Added recall-gate tests (sample-size formula, meanRecall 1.0/0.0, pass, escalate-then-abort), `clusterViaAnn` tests (ARI agreement, large-synthetic completion, gate-abort propagation, degenerate parity), `adjustedRandIndex`/`comb2`/`bruteForceFarthest` test helpers

## Decisions Made
- Recall gate designed around a caller-supplied `(ef, sampleIdx) -> int[][]` function rather than a hard dependency on `HnswKnnIndex`, so the gate's escalate/abort logic is directly unit-testable with a stub, independent of the real ANN library.
- `clusterViaAnn`'s "degraded ANN params" abort test simulates the bad neighbour source via a stub (returning each sampled row's brute-force FARTHEST neighbours, guaranteeing near-zero overlap with the exact nearest reference) rather than crippling the real `HnswKnnIndex` -- consistent with D-09's explicit "no user-facing ANN knobs" design; there is no external way to make the real index fail recall on demand, and doing so would contradict the hidden-defaults requirement anyway. This exercises the identical `gateAnnRecall` wiring `clusterViaAnn`/`annNeighborsWithGate` calls with no surrounding try/catch, so the exception is guaranteed to propagate uncaught and no `LeidenResult` is ever constructed.
- Extracted `annNeighborsWithGate` (build index, gate, then query full graph at the passing ef) as its own package-private method rather than inlining it into `clusterViaAnn`, so future plans (03: primitive-array SNN rewrite; 04: cohort two-pass driver) can call it directly for the cohort-scale ANN graph build without duplicating the gate-wiring logic.

## Deviations from Plan

None - plan executed exactly as written. `AnnRecallException`, the recall-gate helpers, and `clusterViaAnn` all match the plan's `<action>`/`<acceptance_criteria>` shapes; all grep-based acceptance gates (`clusterViaAnn`, `createNormalizedNetworkUsingAssociationStrength`, `public static int[] transferLabels`, `extends RuntimeException`, `meanRecall|exactNeighborsForQ`) pass as specified.

## Issues Encountered
None.

## TDD Gate Compliance

Both tasks were marked `tdd="true"`. As in Plan 01's Task 2, the recall-gate helper (Task 1) and `clusterViaAnn` (Task 2) are new package-private/public APIs whose test assertions reference methods (`gateAnnRecall`, `meanRecall`, `recallSampleSize`, `exactNeighborsForQueries`, `clusterViaAnn`, `annNeighborsWithGate`) that did not exist prior to writing the implementation -- the test behaviors and the implementation's exact method signatures were co-designed (e.g., the `BiFunction<Integer,int[],int[][]>` gate-callback shape used by both the abort-path stub tests and the real `clusterViaAnn` wiring). Both tasks were committed as a single `feat` commit each (test file + implementation together, all tests green before committing) rather than as separate RED (`test(...)`) then GREEN (`feat(...)`) commits. This is the same documented, deliberate deviation from strict RED/GREEN commit-splitting that Plan 01 recorded, for the same underlying reason: the test/implementation API surface was designed together, not discovered by writing a standalone failing test against pre-existing production code.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `LeidenModel.clusterViaAnn` and `annNeighborsWithGate` are ready for Plan 03 to build on: the primitive-array SNN/Jaccard rewrite for cohort scale can call `annNeighborsWithGate` directly to get a recall-gated ANN neighbour list, then feed it into the rewritten (not the current boxed-`HashSet`) weighting step.
- `gateAnnRecall`'s `(ef, sampleIdx) -> int[][]` callback shape generalizes cleanly to the cohort-scale pooled matrix in Plan 04 (same signature, larger `rows`/`n`).
- The measured (passing) recall value returned by `gateAnnRecall` (and available from `clusterViaAnn`'s call path, though not yet surfaced through a public return channel) is what Plan 05's UI status line ("ANN recall 0.982 -- passed", D-09) will need to display -- Plan 05 should add a `clusterViaAnn` overload/variant that also returns the measured recall alongside the `LeidenResult` if the UI needs it directly, or thread it through the cohort driver's log callback.
- No blockers for Plan 03.

---
*Phase: 15-all-cells-leiden-clustering*
*Completed: 2026-07-06*

## Self-Check: PASSED

All claimed files found on disk (`AnnRecallException.java`, `LeidenModel.java`, `LeidenModelTest.java`, this SUMMARY.md); both task commits (`51adcea`, `6882db9`) found in git history.
