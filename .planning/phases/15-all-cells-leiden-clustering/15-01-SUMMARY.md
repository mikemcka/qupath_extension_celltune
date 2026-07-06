---
phase: 15-all-cells-leiden-clustering
plan: 01
subsystem: ml
tags: [hnsw, ann, jelmerk, leiden, knn, java]

# Dependency graph
requires: []
provides:
  - "com.github.jelmerk:hnswlib-core:1.2.1 bundled into the fat JAR (implementation + shadow)"
  - "HnswKnnIndex: pure ANN kNN graph wrapper (static knn() + live build()/queryRow()/setEf() instance form)"
  - "Documented, tested finding that HnswIndex cannot be subclassed from external source in 1.2.1 (bytecode-verified), settling D-13/RESEARCH Pitfall 1 ahead of Plan 02"
affects: [15-02-clusterViaAnn-recall-gate, 15-03-primitive-snn-rewrite, 15-04-two-pass-cohort-driver, 15-05-ui-docs]

# Tech tracking
tech-stack:
  added: ["com.github.jelmerk:hnswlib-core:1.2.1"]
  patterns: ["Pure static/instance model wrapper (no QuPath/JavaFX types), mirroring LeidenModel's convention", "Live-index build-once/query-many-at-escalating-ef pattern for recall-gate ef escalation (D-08)"]

key-files:
  created:
    - src/main/java/qupath/ext/celltune/model/HnswKnnIndex.java
    - src/test/java/qupath/ext/celltune/model/HnswKnnIndexTest.java
  modified:
    - build.gradle.kts

key-decisions:
  - "HnswIndex subclassing to seed assignLevel is genuinely unworkable in jelmerk 1.2.1 (not just difficult) -- verified directly against decompiled bytecode: the only non-fully-private constructor is a compiler-synthetic bridge (ACC_SYNTHETIC) that javac will not resolve from any external source file, even one placed in jelmerk's own package name. Used the plan's sanctioned fallback: single-threaded, fixed-row-order sequential add() for reproducible=true, documented as best-effort (not bit-proven) determinism."
  - "Reproducible-mode determinism empirically verified stable (byte-identical) across 8 repeated fresh-JVM gradle test invocations before accepting the fallback as non-flaky."

requirements-completed: []  # LEI-07/LEI-10 are multi-plan requirements (recall gate = Plan 02, remaining test coverage = Plans 02-05) -- not marking complete until the full requirement is satisfied across all contributing plans.

# Metrics
duration: 24min
completed: 2026-07-06
---

# Phase 15 Plan 01: HNSW Dependency + ANN kNN Wrapper Summary

**Bundled jelmerk hnswlib-core 1.2.1 and built HnswKnnIndex, a pure ANN kNN graph wrapper with a best-effort-deterministic single-threaded build path, after bytecode-level investigation proved the plan's preferred seeded-subclass approach genuinely unworkable in this release.**

## Performance

- **Duration:** 24 min
- **Started:** 2026-07-06T02:14:00Z
- **Completed:** 2026-07-06T02:37:51Z
- **Tasks:** 2 completed
- **Files modified:** 3 (1 modified, 2 created)

## Accomplishments
- `com.github.jelmerk:hnswlib-core:1.2.1` bundled into the fat JAR via the existing `implementation` + `shadow` pairing; `shadowJar` confirmed 53 jelmerk classes present in the bundled JAR with no classpath conflicts.
- `HnswKnnIndex` created: a pure, QuPath/JavaFX-free ANN kNN graph builder providing (a) a one-shot static `knn(rows, k, seed, reproducible)` matching `LeidenModel.featureKnn`'s output contract (self-excluded, `min(k,n-1)` length, ascending (distance, id) order) and (b) a live instance API (`build()`/`queryRow()`/`setEf()`) so a built index can be re-queried at a higher `ef` without rebuilding — required by Plan 02's recall-gate escalation (D-08).
- Resolved the plan's open determinism question empirically: attempted the RESEARCH-recommended `HnswIndex` subclass override of `assignLevel`, discovered via `javap`/bytecode inspection that `HnswIndex` has no source-resolvable constructor outside its own compilation unit (its only non-private constructor is a compiler-synthetic bridge that javac refuses to resolve from external source, even same-package), and fell back to the plan's explicitly sanctioned alternative (single-threaded, fixed-row-order sequential `add()`), documenting the caveat in the class javadoc and validating it empirically (8 repeated fresh-JVM test runs, all byte-identical, no flakiness).
- ANN import surface (`com.github.jelmerk.*`) confined entirely to `HnswKnnIndex.java`; `LeidenModel.java` has zero such imports (grep-verified).

## Task Commits

1. **Task 1: Bundle hnswlib-core into the build** - `a70feda` (feat)
2. **Task 2: HnswKnnIndex wrapper with seeded-deterministic build** - `9d9140f` (feat, tdd — see TDD Gate Compliance note below)

**Plan metadata:** (this commit, pending)

## Files Created/Modified
- `build.gradle.kts` - Added `com.github.jelmerk:hnswlib-core:1.2.1` (implementation + shadow) alongside the existing CWTS block
- `src/main/java/qupath/ext/celltune/model/HnswKnnIndex.java` - New pure ANN kNN wrapper (static `knn()` + live `build()/queryRow()/setEf()`)
- `src/test/java/qupath/ext/celltune/model/HnswKnnIndexTest.java` - Tests A-E: recall vs exact (≥95% mean), self-exclusion + length, reproducible-build determinism, degenerate n=0/n=1, setEf escalation

## Decisions Made
- **Subclassing `HnswIndex` is unworkable in jelmerk 1.2.1, confirmed by direct bytecode inspection** (not just "difficult" as RESEARCH's Open Question 1 anticipated as the risk to hedge against). `javap -v` on the shipped `HnswIndex.class` shows exactly one constructor accessible at the Java source level: a fully `private` one-arg `(RefinedBuilder)` constructor. A second, 2-arg `(RefinedBuilder, HnswIndex$1)` constructor exists in the bytecode but is `ACC_SYNTHETIC` — a compiler-generated bridge used only by javac's own generated call sites inside `HnswIndex.java`'s single compilation unit; a real test-compile of a subclass declared in jelmerk's own package name (`com.github.jelmerk.hnswlib.core.hnsw`) confirmed javac's overload resolution does not consider it, failing with "constructor cannot be applied to given types." This closes RESEARCH's Open Question 1 definitively rather than leaving it as an executor judgment call.
- **Used the plan's explicitly pre-authorized fallback**: single-threaded, fixed-row-order sequential `add()` when `reproducible=true`, removing the concurrent-insertion-order source of nondeterminism (jelmerk's parallel `addAll` links nodes in whatever order threads win an internal lock). This is documented in the class javadoc as "best-effort, not bit-proven" determinism, per the plan's own permitted fallback language. Reliability was verified empirically (not just asserted): the determinism test was run 8 times across independent `gradlew` invocations (fresh JVM/thread each time, so `ThreadLocalRandom`'s per-JVM seed genuinely differs run to run) and passed every time with byte-identical output, giving confidence the hidden-default `M=16`/`efConstruction=200`/`ef=64` combination is generous enough that graph-topology randomness does not, in practice, change the returned neighbour sets for well-clustered marker-style data.
- Chose hidden defaults `M=16`, `efConstruction=200`, initial `ef=64` (D-09) — no user-facing ANN knobs; `ef` is raised at query time via `setEf()` per D-08 without any rebuild.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] `HnswIndex` subclassing (the plan's primary determinism approach) does not compile from source; used the plan's own sanctioned fallback**
- **Found during:** Task 2 (HnswKnnIndex wrapper implementation)
- **Issue:** The plan's `<action>` instructs subclassing `HnswIndex` to override `public int assignLevel(double lambda)` with a seeded `Random`, on the stated premise that "`assignLevel` is public and `HnswIndex` is non-final → subclassable." Investigation (decompiling the shipped 1.2.1 `HnswIndex.class` with `javap -v` and a real test-compile) showed `HnswIndex` has no constructor resolvable from external source — the plan's premise about straightforward subclassing does not hold for this release.
- **Fix:** The plan itself anticipated this possibility and pre-authorized a fallback ("If subclassing proves unworkable in 1.2.1 (constructor/access), FALL BACK to single-threaded ordered `add()` only ... but the determinism test (Test C) MUST pass — do not ship a flaky reproducible mode"). Implemented exactly that fallback, then empirically validated non-flakiness via 8 repeated fresh-JVM test runs before accepting it.
- **Files modified:** `src/main/java/qupath/ext/celltune/model/HnswKnnIndex.java` (javadoc documents the investigation and the accepted caveat)
- **Verification:** `./gradlew test --tests "qupath.ext.celltune.model.HnswKnnIndexTest"` green; determinism test (`reproducibleBuildIsByteIdenticalAcrossTwoConsecutiveRuns`) specifically re-run 8x via `--rerun-tasks`, all passing
- **Committed in:** `9d9140f` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking — plan's stated subclassing premise did not hold; used the plan's own pre-authorized fallback)
**Impact on plan:** No scope creep — the fallback path was explicitly written into the plan's own `<action>` text for exactly this contingency. The investigation strengthens confidence versus simply assuming the fallback was needed: subclassing is now known, not just suspected, to be unworkable in 1.2.1, closing RESEARCH's Open Question 1.

## Issues Encountered
None beyond the subclassing investigation documented above.

## TDD Gate Compliance

Task 2 was marked `tdd="true"`. Because determining the correct implementation required an API-exploration spike (decompiling jelmerk's bytecode to establish what `assignLevel`/constructor access actually looks like in 1.2.1, and test-compiling a throwaway subclass to confirm the subclassing approach fails), the implementation and its test file were written together and committed in a single `feat` commit (`9d9140f`) rather than as separate RED (`test(...)`) then GREEN (`feat(...)`) commits. Both the test file and implementation were validated together (all 6 tests green, plus 8 repeated runs of the determinism test) before committing. This is a deliberate, documented deviation from the strict RED/GREEN commit-splitting convention — the spike work needed to happen before either a meaningful failing test or a correct implementation could be written, since the test behaviors themselves (e.g., what `reproducible=true` actually guarantees) depended on the spike's outcome.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `HnswKnnIndex` is ready for Plan 02 to route `LeidenModel.cluster`'s kNN graph construction through it (single-image scope) and to build the recall gate on top of its `build()/queryRow()/setEf()` instance API.
- The determinism caveat (best-effort, not bit-proven) is now documented in-code and in this summary — Plan 02/05 should carry this caveat into any user-facing reproducibility documentation (D-13) rather than promising bit-identical ANN graph topology.
- No blockers for Plan 02.

---
*Phase: 15-all-cells-leiden-clustering*
*Completed: 2026-07-06*

## Self-Check: PASSED

All claimed files found on disk; both task commits (`a70feda`, `9d9140f`) found in git history.
