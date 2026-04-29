# Phase 10 Code Review

Date: 2026-04-29
Scope:
- src/main/java/qupath/ext/celltune/CellTuneExtension.java
- src/main/java/qupath/ext/celltune/ui/ProjectPredictionSummaryView.java
- src/main/resources/qupath/ext/celltune/ui/strings.properties

## Findings

### Severity: Low

1. Ambiguous project image lookup when duplicate display names exist.
- File: src/main/java/qupath/ext/celltune/ui/ProjectPredictionSummaryView.java
- Detail: `openSelectedImage()` resolves the selected row by `entry.getImageName()` equality. If a project contains multiple image entries with the same display name, the first match is opened and may not be the intended row target.
- Risk: Users may review/edit a different image than expected in edge-case projects with duplicate names.
- Recommendation: Carry a stable image entry identifier in the summary row model (for example URI/server path or entry ID) and resolve by that identifier instead of display name.

## No Blocking Defects

No critical, high, or medium severity defects were identified in the reviewed scope. The summary menu wiring, dialog rendering, open-image action, and CSV export flow are functionally coherent.

## Residual Risk

- The low-severity duplicate-name lookup risk should be tracked as a follow-up hardening task.
- Manual validation in QuPath remains important for menu visibility and open-image behavior because these paths depend on runtime project state.

## Verification Notes

- Summary menu key exists in resource bundle: `menu.prediction.summary`.
- Extension menu wiring includes `projectSummaryItem` and invokes `showProjectPredictionSummary(qupath)`.
- Dialog includes action buttons for `Open Selected Image` and `Export CSV` and handles empty-row/export-empty states.
