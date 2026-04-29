# Phase 03 Gap Context: Composite Rule Contract and Persistence

Source: .planning/v1.0-MILESTONE-AUDIT.md

## Requirements Closed in This Phase

- COMP-01: User can define a composite rule as marker-polarity pairs.
- COMP-04: Named composite rules persist in project storage and can be reused across sessions.

## Gaps to Close

- Missing explicit composite rule model containing marker and polarity payload.
- Missing named-rule persistence store (expected composite-rules.json).
- Broken save/reload composite flow for reusable rule definitions.

## Expected Outputs

- Domain model for named composite rules with marker polarity tuples.
- Persistence APIs and storage format for named rules.
- Integration path from UI selection to classifier execution using explicit rule polarity.
- Verification evidence for create, save, load, and apply named rule behavior.
