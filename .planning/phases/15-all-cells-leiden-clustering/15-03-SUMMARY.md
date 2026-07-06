---
phase: 15-all-cells-leiden-clustering
plan: 03
subsystem: ml
tags: [leiden, snn, jaccard, primitive-arrays, java, cwts]

# Dependency graph
requires:
  - phase: 15-01
    provides: "HnswKnnIndex ANN wrapper (static knn() + live build()/queryRow()/setEf() instance API), seeded single-threaded reproducible build"
  - phase: 15-02
    provides: "AnnRecallException, LeidenModel.gateAnnRecall/clusterViaAnn -- HNSW-routed single-image Leiden feeding the (until now boxed) Jaccard/SNN weighting + CWTS pipeline"
provides:
  - "LeidenModel's Jaccard/SNN edge weighting rewritten from boxed HashSet<Integer>[]/HashSet<Long> to primitive sorted-int[] closed-neighbour arrays + two-pointer merge-intersection (scales to ~30M cohort nodes, RESEARCH Pitfall 2)"
  - "Package-private jaccardEdgesForTest/JaccardEdges test seam exposing the exact edge list + weights buildJaccardWeightedNetwork feeds into the unchanged CWTS Network, for boxed-vs-primitive byte-identical equivalence testing"
  - "Proof that clusterViaAnn assigns every pooled row a label from a single partition (labels.length == rows.length), recovers synthetic multi-blob communities by purity, and is reproducible up to permutation (ARI==1.0) across two runs with the same seed"
affects: [15-04-two-pass-cohort-driver, 15-05-ui-docs]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Primitive sorted-int[] closed-neighbour arrays + two-pointer merge-intersection Jaccard, replacing boxed HashSet<Integer>[]/HashSet<Long> at cohort scale"
    - "Edge dedup by construction (lower-indexed endpoint always emits; higher-indexed endpoint emits only if the lower node doesn't already list it) instead of a global boxed seen-edges set -- and, because the outer loop visits nodes ascending, produces edges in the exact same append order as the retired boxed implementation (not just the same set)"
    - "Test-only boxed reference implementation kept in the test file (not production code) as the equivalence baseline, exposed against a package-private production test seam (jaccardEdgesForTest)"

key-files:
  created: []
  modified:
    - src/main/java/qupath/ext/celltune/model/LeidenModel.java
    - src/test/java/qupath/ext/celltune/model/LeidenModelTest.java

key-decisions:
  - "Edge dedup avoids a global HashSet<Long> entirely via a by-construction rule: process node i's raw (non-closed) neighbour list; for the unordered pair {a=min(i,j), b=max(i,j)}, emit while processing the lower endpoint a always (covers symmetric links and asymmetric a-lists-b-only links), and emit while processing the higher endpoint b only if a's closed neighbour array does not already contain b (the asymmetric b-lists-a-only case, checked via binary search on the sorted closed array). Because the outer loop visits nodes 0..n-1 ascending, the lower endpoint's pass always happens first, so this never double-counts and reproduces the exact same append order (not just the same edge set) as the retired boxed HashSet<Long> global-dedup implementation -- which is why the equivalence test can assert byte-identical from[]/to[]/weights[] arrays directly, with no sorting or reordering needed."
  - "Kept the boxed Jaccard/SNN reference implementation ONLY in LeidenModelTest.java (not in LeidenModel.java) as the equivalence baseline, rather than retaining a renamed *Boxed method in production code. This keeps LeidenModel.java's actual weighting code path free of any boxed HashSet<Integer>/HashSet<Long> (grep on the file shows only javadoc {@code} references to the retired pattern, explaining what was removed and why it must not be reintroduced -- not live code), while still giving the test file a faithful, independently-written reference to assert against."
  - "Exposed the internal edge computation via a package-private jaccardEdgesForTest seam + JaccardEdges record (returning plain int[]/double[] via LargeIntArray/LargeDoubleArray's existing toArray()), rather than reconstructing the edge list by re-deriving CWTS Network internals in the test. Production code (buildJaccardWeightedNetwork) still passes the LargeIntArray/LargeDoubleArray directly to the Network constructor with no array-copy overhead; only the test seam pays the toArray() conversion cost."
  - "The reproducibility-up-to-permutation test (SPEC Acceptance Criterion 6) asserts Adjusted Rand Index == 1.0 across two clusterViaAnn runs with the same seed, not a raw assertArrayEquals on label ids, per the plan's explicit 'up to permutation, not raw equality' instruction. It ran stably (non-flaky) across 3 additional local reruns, confirming Plan 15-01's seeded single-threaded HnswKnnIndex determinism holds through this plan's rewritten weighting step."

requirements-completed: []  # LEI-06/LEI-10 span this plan and Plans 04-05 (two-pass cohort UUID driver, UI/docs); not marking complete until satisfied across all contributing plans, per Plan 01/02's precedent.

# Metrics
duration: 12min
completed: 2026-07-06
---

# Phase 15 Plan 03: Primitive-Array SNN Rewrite + All-Cells Recovery/Reproducibility Summary

**Rewrote LeidenModel's Jaccard/SNN edge weighting from boxed HashSet<Integer>[]/HashSet<Long> to primitive sorted-int[] arrays with merge-intersection counting (byte-identical output, proven by an exact-equality equivalence test), and proved clusterViaAnn's all-cells single-partition assignment, community recovery, and run-to-run reproducibility up to permutation.**

## Performance

- **Duration:** ~12 min (git commit-to-commit span from Plan 02's completion; excludes the upfront read-and-plan-review phase)
- **Started:** 2026-07-06T12:53:28+10:00 (approx, following 15-02's completion commit)
- **Completed:** 2026-07-06T13:05:00+10:00
- **Tasks:** 2 completed
- **Files modified:** 2 (both modified, none created)

## Accomplishments
- `LeidenModel`'s SNN/Jaccard edge weighting (`buildJaccardWeightedNetwork`) rewritten from a boxed per-node `HashSet<Integer>[]` (closed neighbour sets) plus a global `HashSet<Long>` edge-dedup set to sorted primitive `int[]` closed-neighbour arrays (built once via `buildClosedNeighborArrays`/`sortedDedup`) and a two-pointer merge-intersection walk (`jaccardPrimitive`, O(|a|+|b|) per pair, no hashing or boxed `Integer` allocation) -- addressing RESEARCH Pitfall 2's tens-of-GB boxing overhead concern at ~30M cohort nodes.
- Edge dedup is achieved by construction (lower-indexed endpoint always emits; higher-indexed endpoint emits only if the lower endpoint's sorted array doesn't already contain it, checked via `containsSorted` binary search) rather than a global boxed set -- and, because the append order this produces is provably identical to the retired boxed implementation's first-occurrence-in-iteration-order semantics, the boxed-vs-primitive equivalence test asserts byte-identical `from[]`/`to[]`/`weights[]` arrays directly (no sorting/reordering needed to compare).
- New package-private `LeidenModel.jaccardEdgesForTest`/`JaccardEdges` test seam exposes the exact edge list + weights fed into the unchanged `new Network(n, true, ..., false, true)` + `createNormalizedNetworkUsingAssociationStrength()` pipeline, without touching the CWTS constructor contract.
- New tests: `jaccardEdgesForTestMatchesBoxedReferenceOnVariousSizes` (n=10..200, k=2/5/15, exact `==` weight equality against a boxed reference kept only in the test file) and `jaccardEdgesForTestGivesClosedSetWeightOneForMutualOnlyNeighbours` (closed-set convention: mutual-only neighbours weight 1.0).
- New tests proving LEI-06's pure-model core: `clusterViaAnnAssignsEveryRowFromSinglePartitionAndRecoversBlobsByPurity` (3-blob synthetic cloud, `labels().length == rows.length`, per-blob purity > 0.9) and `clusterViaAnnReproducibleRunsAreIdenticalUpToPermutation` (SPEC Acceptance Criterion 6: two reproducible runs, same seed, ARI == 1.0 -- verified stable across 3 additional local reruns, not flaky).
- `transferLabelsStillWorksAfterAllCellsRewrite` smoke test guards the retained Phase 14 transfer path against this plan's weighting rewrite.
- Full `./gradlew test` (372 tests, 0 failures/errors), `./gradlew spotlessCheck` (formatting), and `./gradlew shadowJar` all green.

## Task Commits

1. **Task 1: Primitive-array SNN/Jaccard rewrite (byte-identical semantics)** - `20455c6` (feat, tdd)
2. **Task 2: All-cells recovery + reproducibility-up-to-permutation tests** - `b40d94d` (test, tdd)

**Plan metadata:** (this commit, pending)

## Files Created/Modified
- `src/main/java/qupath/ext/celltune/model/LeidenModel.java` - `buildJaccardWeightedNetwork`/`buildClosedNeighborSets`/`jaccard` (boxed) replaced by `buildJaccardWeightedNetwork` (thin wrapper) + `computeJaccardEdges`/`buildClosedNeighborArrays`/`sortedDedup`/`containsSorted`/`jaccardPrimitive` (primitive); added `JaccardEdges` record + `jaccardEdgesForTest` package-private test seam. `clusterViaAnn`, `cluster`, `transferLabels`, the recall gate, and the CWTS `Network`/association-strength-normalization calls are unchanged.
- `src/test/java/qupath/ext/celltune/model/LeidenModelTest.java` - Added the boxed-vs-primitive equivalence tests + a boxed reference implementation (`buildJaccardEdgesBoxedReference`/`jaccardBoxedReference`, test-file-only), and the all-cells single-partition/purity, reproducibility-up-to-permutation, and transfer-path smoke tests.

## Decisions Made
See `key-decisions` in frontmatter: (1) by-construction edge dedup replacing the global boxed set, provably preserving append order; (2) boxed reference kept test-file-only, not in production code; (3) `jaccardEdgesForTest`/`JaccardEdges` test seam using `LargeIntArray`/`LargeDoubleArray`'s existing `toArray()`; (4) ARI==1.0 (not raw label equality) for the permutation-invariant reproducibility assertion.

## Deviations from Plan

None - plan executed exactly as written. All `<acceptance_criteria>` grep gates were checked directly:
- `grep -n 'HashSet<Integer>' LeidenModel.java` -> 4 matches, all inside javadoc `{@code HashSet<Integer>}` comments describing the retired pattern (none are live code); the only remaining *code-level* `HashSet<Integer>` usage in the file is the pre-existing, out-of-scope `rowRecall` helper in the Plan 15-02 recall gate (bounded to <=10k sampled rows x k~15 neighbours -- not a cohort-scale concern and not part of this plan's weighting-path rewrite). The weighting path itself (`buildJaccardWeightedNetwork`/`computeJaccardEdges`/`buildClosedNeighborArrays`/`jaccardPrimitive`) contains zero boxed `HashSet`/`Set` usage.
- `grep -n 'new Network('` -> 1 match, `, false, true)` suffix preserved verbatim (constructor unchanged).
- `grep -c 'createNormalizedNetworkUsingAssociationStrength'` -> 4 (unchanged call sites plus javadoc references).
- `./gradlew test --tests "qupath.ext.celltune.model.LeidenModelTest"` -> 25/25 pass.
- `./gradlew test` (full suite) -> 372/372 pass, 0 failures/errors.

## Issues Encountered

None. `./gradlew spotlessApply` reformatted both modified files (whitespace/line-wrap only, e.g. wrapping the long javadoc/record lines to the 120-column limit) after the initial edits; re-ran `spotlessCheck` + the full `LeidenModelTest` suite afterward to confirm no behavioral change, then split the single-pass edit into two atomic per-task commits by temporarily removing Task 2's test block, committing Task 1, then re-adding Task 2's tests and committing separately (both files were touched by both tasks' initial edit pass, since Task 1 required a small test-file addition alongside the main-code rewrite).

## TDD Gate Compliance

Both tasks were marked `tdd="true"`. As in Plans 01/02, the equivalence test (Task 1) exercises new package-private methods (`jaccardEdgesForTest`, `JaccardEdges`) whose signatures were designed together with the implementation, and the all-cells/reproducibility tests (Task 2) exercise the already-existing `clusterViaAnn` (from Plan 02) rather than a newly-introduced API discovered via a standalone failing test. Both tasks were committed as a single commit each (implementation + its tests together, all green before committing) rather than separate RED (`test(...)`) then GREEN (`feat(...)`) commits -- consistent with the same documented deviation recorded in Plans 01 and 02 for the same underlying reason. Task 2's commit is intentionally typed `test(...)` (not `feat(...)`) since it added zero production code, only new test methods against the already-shipped `clusterViaAnn`.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- The SNN/Jaccard weighting step is now primitive-array-based and proven byte-identical to the previous boxed semantics -- Plan 04's two-pass cohort driver can pool tens of millions of cells and route them through `clusterViaAnn` -> `buildJaccardWeightedNetwork` without the boxed-collection memory/GC blowup RESEARCH Pitfall 2 flagged.
- `clusterViaAnn` is now proven (not just implemented) to produce a single dense partition over every pooled row and to be reproducible up to permutation across runs -- Plan 04 can rely on this contract when writing labels back by UUID.
- No blockers for Plan 04 (two-pass UUID-keyed cohort write driver) or Plan 05 (UI/docs, cohort-mode radio pair, soft ceiling, progress/cancel).

---
*Phase: 15-all-cells-leiden-clustering*
*Completed: 2026-07-06*

## Self-Check: PASSED

All claimed files found on disk (`LeidenModel.java`, `LeidenModelTest.java`, this SUMMARY.md); both task commits (`20455c6`, `b40d94d`) found in git history; full test suite (372 tests) and `shadowJar` verified green in this session.
