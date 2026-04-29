---
phase: 04-composite-rule-builder-ux-and-verification-hardening
requirement: COMP-02
status: gaps_found
verified_on: 2026-04-29
---

# Phase 04 Verification: Composite Rule Builder UX

## Scope

This verification artifact captures COMP-02 evidence for:
- per-marker + / - / ignore controls
- required user rule naming
- persisted rule reload into marker-row states
- current-image and batch-image apply behavior

## Scenario Checklist

| Scenario | Expected | Observed | Result |
|----------|----------|----------|--------|
| Marker row controls | Composite dialog displays each trained marker with explicit +, -, and ignore controls (ignore default). | Pending checkpoint | Pending |
| Rule naming required | Save/apply is blocked when rule name is empty with clear validation feedback. | Pending checkpoint | Pending |
| Non-ignore marker required | Save/apply is blocked when all markers are ignore with clear validation feedback. | Pending checkpoint | Pending |
| Save and reload mapping | After save + reopen, selecting saved rule restores row states for markers in the rule and leaves unspecified markers as ignore. | Pending checkpoint | Pending |
| Current-image apply | Applying a valid row-based rule on open image returns matched-cell summary without expression typing. | Pending checkpoint | Pending |
| Batch apply | Applying to selected batch images returns per-image result entries. | Pending checkpoint | Pending |

## Requirement Traceability

- COMP-02: Covered by all scenarios above.

## Notes

- Status remains `gaps_found` until checkpoint verification is completed and all scenario results are filled.
