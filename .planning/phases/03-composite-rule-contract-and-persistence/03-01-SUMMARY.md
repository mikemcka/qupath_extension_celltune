---
phase: 03-composite-rule-contract-and-persistence
plan: 01
subsystem: classifier
tags: [composite-rules, persistence, gson, qupath]
requires:
  - phase: 02-composite-classification-builder
    provides: marker-based composite apply flow and dialog entrypoint
provides:
  - immutable composite rule contract with marker polarity tuples
  - versioned composite-rules JSON persistence APIs
  - resilient rule loading that skips malformed entries
affects: [03-02-rule-wiring, composite-classification-dialog, milestone-audit]
tech-stack:
  added: []
  patterns: [value-object-rule-contract, versioned-json-envelope, partial-load-validation]
key-files:
  created:
    - src/main/java/qupath/ext/celltune/classifier/CompositeClassificationRule.java
  modified:
    - src/main/java/qupath/ext/celltune/io/ProjectStateManager.java
key-decisions:
  - "Persist rules as name + canonical expression + condition array in composite-rules.json"
  - "Load path is tolerant: malformed rules are skipped instead of failing the full file"
patterns-established:
  - "Rule contract validates marker safety via BinaryClassifierRegistry-compatible constraints"
  - "Persistence schema is versioned to support future rule format changes"
requirements-completed: [COMP-01, COMP-04]
duration: 26 min
completed: 2026-04-29
---

# Phase 03 Plan 01: Composite Rule Contract and Persistence Summary

**Named composite rule value model with versioned project persistence at composite-rules.json**

## Performance

- **Duration:** 26 min
- **Started:** 2026-04-29T00:00:00Z
- **Completed:** 2026-04-29T00:26:00Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- Added `CompositeClassificationRule` as an immutable rule contract with marker polarity conditions.
- Added parse/format helpers for canonical rule expressions such as `CD4+:CD3+:CD20-`.
- Added `ProjectStateManager.saveCompositeRules/loadCompositeRules` for versioned `composite-rules.json` persistence.
- Added resilient loading that logs and skips malformed rule entries while keeping valid ones.

## Task Commits

Each task was committed atomically:

1. **Task 1: Add explicit composite rule contract with parse/format validation** - `9e90666` (feat)
2. **Task 2: Persist named composite rules in ProjectStateManager** - `3642f4c` (feat)

## Files Created/Modified
- `src/main/java/qupath/ext/celltune/classifier/CompositeClassificationRule.java` - Immutable named-rule contract with validation + expression parsing/formatting.
- `src/main/java/qupath/ext/celltune/io/ProjectStateManager.java` - Added composite-rules save/load APIs and malformed-entry tolerant parsing helpers.

## Decisions Made
- Persist both canonical expression and expanded condition list to future-proof rule deserialization.
- Preserve backward compatibility with existing `composite-config.json` marker-selection APIs.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] PowerShell file encoding inserted BOM at start of ProjectStateManager**
- **Found during:** Task 2 verification (`./gradlew compileJava`)
- **Issue:** Java compile failed with illegal `\ufeff` character at file start.
- **Fix:** Rewrote `ProjectStateManager.java` as UTF-8 without BOM and re-ran compile.
- **Files modified:** `src/main/java/qupath/ext/celltune/io/ProjectStateManager.java`
- **Verification:** `./gradlew compileJava` passed after rewrite.
- **Committed in:** `3642f4c` (part of Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** No scope change. Fix was required to restore compilation and complete planned persistence work.

## Issues Encountered
- Initial scripted edit path produced PowerShell quoting errors; moved to a temporary patch script for deterministic insertion.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Phase 3 plan 2 can now wire dialog and classifier execution to named rules using the new persistence APIs.
- Existing marker-selection config remains available for phased migration.

---
*Phase: 03-composite-rule-contract-and-persistence*
*Completed: 2026-04-29*
