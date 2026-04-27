---
status: complete
phase: 01-binary-classifier-infrastructure
source:
  - 01-01-SUMMARY.md
  - 01-02-SUMMARY.md
started: 2026-04-27T00:00:00
updated: 2026-04-27T00:00:00
---

## Current Test

number: complete
name: All tests passed
awaiting: none

## Tests

### 1. Menu item appears
expected: Extensions > CellTune > "Binary Classifiers..." item exists near the top of the menu, just below "Classify..."
result: pass

### 2. Dialog opens and lists markers
expected: Clicking "Binary Classifiers..." opens a modal dialog titled "Binary Classifiers — CellTune" showing an empty marker list and three buttons: Create..., Open (disabled), Delete (disabled).
result: pass

### 3. Create a binary classifier
expected: Clicking "Create..." opens a text input prompt. Typing "CD4" and confirming adds "CD4" to the marker list and the Open/Delete buttons become enabled.
result: pass

### 4. Special characters are sanitized in marker names
expected: Typing "CD4+" in the Create dialog results in "CD4_" being added (the + is replaced with _), not "CD4+". The list shows the sanitized name.
result: pass

### 5. Open a binary classifier enters binary mode
expected: Selecting "CD4" in the list and clicking "Open" closes the dialog, expands the docked CellTune panel, and shows a blue "Active binary mode: CD4" banner inside the panel along with an "Exit Binary Mode" button.
result: pass
note: Two bugs fixed — dialog wasn't closing (added window.hide()); panel wasn't expanding (getParent() returned skin node, now uses stored dockPane/dockTab refs)

### 6. Multi-class workflow is unaffected when not in binary mode
expected: With no binary classifier open (activeBinaryMarker == null), the CellTune panel functions exactly as before — landmark, classify, review, export all work normally with no new UI elements visible.
result: pass

### 7. Exit binary mode restores multi-class state
expected: While in binary mode (e.g. CD4 active), clicking "Exit Binary Mode" hides the blue banner, removes the exit button, and the panel returns to showing the original multi-class label store / classifier state.
result: pass

### 8. Multiple binary classifiers coexist
expected: Opening "Binary Classifiers..." again, creating "CD3" and "CD20" works. All three (CD4, CD3, CD20) appear in the list simultaneously. Each can be opened independently.
result: pass
note: Binary mode banner also added to docked ClassificationPanel after this test revealed it was missing

### 9. Delete a binary classifier
expected: Selecting "CD4" and clicking "Delete" shows a confirmation dialog ("Delete binary classifier 'CD4'? This cannot be undone."). Confirming removes "CD4" from the list. The binary/<marker>.json file is deleted from the project folder.
result: pass

### 10. State persists across QuPath restart
expected: After creating a binary classifier named "CD4" and seeding some labels (without necessarily training a model), closing QuPath and reopening the project — "Binary Classifiers..." still shows "CD4" in the list, loaded from binary-registry.json.
result: pass
note: Registry correctly persisted CD3 across restart. CD4 and CD20 were absent because they were deleted (Test 9 and manually) before restart — expected. Newly created CD4 has no .json state file until labels are saved — also expected.

## Summary

total: 10
passed: 10
issues: 0
skipped: 0
blocked: 0
pending: 0

## Gaps

```yaml
- truth: "Clicking Open with a marker selected enters binary mode — dialog closes, docked panel expands, blue banner appears"
  status: failed
  reason: "User reported: when I click open nothing happens even when highlighting CD4"
  severity: major
  test: 5
  artifacts: []
  missing: []
```
