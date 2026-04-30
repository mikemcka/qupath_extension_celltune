---
phase: 11-project-summary-cohort-outlier-analytics
plan: 01
type: execute
status: completed
completed: 2026-04-30
requirements_completed: [COH-01, COH-02, COH-04]
artifacts:
  - src/main/java/qupath/ext/celltune/model/CohortAnomalyReport.java
  - src/main/java/qupath/ext/celltune/model/CohortAnomalyAnalyzer.java
  - src/test/java/qupath/ext/celltune/model/CohortAnomalyAnalyzerTest.java
verification:
  - grep -nE "class CohortAnomalyReport|record ImageAnomaly|record ClassEnrichment|RARE_|OUTLIER_" src/main/java/qupath/ext/celltune/model/CohortAnomalyReport.java
  - ./gradlew test --tests "qupath.ext.celltune.model.CohortAnomalyAnalyzerTest"
---

# Phase 11 Plan 01 Summary

Implemented a model-layer cohort analytics engine for rare-type enrichment and image-level anomaly scoring.

## Delivered

- Added immutable contracts in `CohortAnomalyReport` for:
  - per-image anomaly outputs
  - per-class enrichment metrics
  - explicit threshold defaults and reason tags
- Added `CohortAnomalyAnalyzer` with:
  - smoothed baseline and per-image class fractions
  - enrichment fold scoring
  - Jensen-Shannon composition distance
  - robust z-scores via median/MAD
  - weighted anomaly aggregation and reason-tag generation
- Added deterministic unit coverage in `CohortAnomalyAnalyzerTest` for:
  - rare enrichment detection
  - outlier ranking behavior
  - balanced cohort non-flag behavior

## Notes

- Sorting was fixed to ensure descending anomaly ranking is stable and deterministic.
- Test execution required adding JUnit platform launcher runtime dependency for Gradle 9.
