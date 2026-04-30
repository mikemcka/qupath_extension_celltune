---
phase: 11-project-summary-cohort-outlier-analytics
plan: 02
type: execute
status: completed
completed: 2026-04-30
requirements_completed: [COH-03, COH-04]
artifacts:
  - src/main/java/qupath/ext/celltune/CellTuneExtension.java
  - src/main/java/qupath/ext/celltune/ui/ProjectPredictionSummaryView.java
  - src/main/java/qupath/ext/celltune/io/ProjectSummaryCsvExporter.java
  - src/test/java/qupath/ext/celltune/io/ProjectSummaryCsvExporterTest.java
verification:
  - grep -nE "CohortAnomalyAnalyzer|anomalyScore|flagged|flagReasons|buildPredictionSummaryRow" src/main/java/qupath/ext/celltune/CellTuneExtension.java
  - grep -nE "Anomaly|Flagged|Target class|strict|balanced|sensitive|Filtered" src/main/java/qupath/ext/celltune/ui/ProjectPredictionSummaryView.java
  - ./gradlew test --tests "qupath.ext.celltune.io.ProjectSummaryCsvExporterTest"
---

# Phase 11 Plan 02 Summary

Integrated cohort anomaly analytics into the project summary dialog and export workflow.

## Delivered

- Refactored summary assembly in `CellTuneExtension` to:
  - build analyzer inputs from per-image predictions
  - run cohort analysis once per summary request
  - merge anomaly and enrichment results into row payloads
- Upgraded `ProjectPredictionSummaryView` with:
  - anomaly score and flagged columns
  - `Flagged only` filter
  - target-class filter for highlighted rare classes
  - strict/balanced/sensitive threshold presets
  - expanded detail panel with reasons and rare-enrichment text
- Added `ProjectSummaryCsvExporter` to centralize CSV schema and escaping.
- Added `ProjectSummaryCsvExporterTest` for schema and escaping regression coverage.

## Notes

- Export now includes anomaly and rare-enrichment fields for downstream triage workflows.
- Existing open-image navigation behavior was preserved.
