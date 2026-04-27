# Roadmap â€” CellTune QuPath Extension

## Milestone 1 â€” Binary Composite Classification

Add per-marker binary classification with composite population assembly.
Users train independent `pos/neg` classifiers per marker and combine them
into composite populations (e.g. `CD4+:CD3+:CD20-`) written as QuPath PathClasses.

**Reference:** `.planning/notes/binary-composite-classification-design.md`

---

### Phase 1 â€” Binary Classifier Infrastructure

**Goal:** Enable users to create, train, review, and persist independent binary (pos/neg) classifiers per marker, fully reusing the existing dual-model active learning loop.

**Depends on:** Existing system (all phases complete)

**Deliverables:**
- `BinaryClassifierRegistry` â€” tracks all named binary classifiers in the project; persists marker name â†’ state file mapping in `<project>/celltune/binary-registry.json`
- Named binary classifier sessions â€” each marker gets its own `LabelStore` (2 classes: `<Marker>_pos` / `<Marker>_neg`), `ClassifierState` saved at `<project>/celltune/binary/<MarkerName>.json`
- Binary marker management panel â€” create new binary classifier (name it by marker), open existing, delete
- Launch existing training/review loop for a selected binary classifier (reuses `DualModelClassifier`, `ReviewController`, `UncertaintySampler` unchanged)
- Menu item: *Extensions > CellTune > Binary Classifiers...*

**UAT:**
- User can create a binary classifier named "CD4", seed `CD4_pos` and `CD4_neg` labels, train, review, and save
- State persists across QuPath restart â€” classifier reloads from `binary/CD4.json`
- Multiple binary classifiers can exist in same project simultaneously (CD4, CD3, CD20)
- Existing multi-class classification workflow is unaffected

---

### Phase 2 â€” Composite Classification Builder

**Goal:** Allow users to define composite population rules from trained binary classifiers (e.g. `CD4+:CD3+:CD20-`) and apply them to write QuPath PathClasses on cells.

**Depends on:** Phase 1 (Binary Classifier Infrastructure)

**Deliverables:**
- `CompositeClassificationRule` â€” stores a list of `(markerName, polarity)` pairs; serialisable to JSON; named by user (e.g. "CD4 T cell")
- `CompositeClassifier` â€” loads N binary `ClassifierState`s, applies 50% hard threshold per classifier, ANDs results, writes composite `PathClass` (e.g. `CD4+:CD3+:CD20-`) to each `PathDetectionObject` via `Platform.runLater()`
- Composite builder dialog â€” shows all trained binary classifiers as rows with `+` / `-` / `ignore` toggles; user names the composite; apply button runs `CompositeClassifier`
- Composite rule persistence â€” named rules saved to `<project>/celltune/composite-rules.json`
- Batch composite classification â€” apply a composite rule across multiple project images (reuses `ImageSelectionPane`)
- Menu item: *Extensions > CellTune > Build Composite Classification...*

**UAT:**
- User with trained CD4, CD3, CD20 binary classifiers can define rule `CD4+:CD3+:CD20-`, apply it, and see cells coloured by that PathClass in the viewer
- Cells not matching any rule retain their previous PathClass or are assigned `Unclassified`
- Composite result appears in Cell Table CSV and AnnData exports
- Named composite rules persist across QuPath restart
- Applying to batch images works when all constituent binary classifiers are trained

**Plans:** 3 plans

Plans:
- [ ] 02-01-PLAN.md — CompositeClassifier (inference engine) + ProjectStateManager composite config methods
- [ ] 02-02-PLAN.md — CompositeClassificationDialog (modal UI: checkbox list, Apply, Batch, progress)
- [ ] 02-03-PLAN.md — CellTuneExtension wiring (menu item + export shortcut + human verify checkpoint)

---
