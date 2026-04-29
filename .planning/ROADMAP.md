# Roadmap ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â CellTune QuPath Extension

## Milestone 1 ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â Binary Composite Classification

Add per-marker binary classification with composite population assembly.
Users train independent `pos/neg` classifiers per marker and combine them
into composite populations (e.g. `CD4+:CD3+:CD20-`) written as QuPath PathClasses.

**Reference:** `.planning/notes/binary-composite-classification-design.md`

---

### Phase 1 ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â Binary Classifier Infrastructure

**Goal:** Enable users to create, train, review, and persist independent binary (pos/neg) classifiers per marker, fully reusing the existing dual-model active learning loop.

**Depends on:** Existing system (all phases complete)

**Deliverables:**
- `BinaryClassifierRegistry` ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â tracks all named binary classifiers in the project; persists marker name ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ state file mapping in `<project>/celltune/binary-registry.json`
- Named binary classifier sessions ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â each marker gets its own `LabelStore` (2 classes: `<Marker>_pos` / `<Marker>_neg`), `ClassifierState` saved at `<project>/celltune/binary/<MarkerName>.json`
- Binary marker management panel ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â create new binary classifier (name it by marker), open existing, delete
- Launch existing training/review loop for a selected binary classifier (reuses `DualModelClassifier`, `ReviewController`, `UncertaintySampler` unchanged)
- Menu item: *Extensions > CellTune > Binary Classifiers...*

**UAT:**
- User can create a binary classifier named "CD4", seed `CD4_pos` and `CD4_neg` labels, train, review, and save
- State persists across QuPath restart ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â classifier reloads from `binary/CD4.json`
- Multiple binary classifiers can exist in same project simultaneously (CD4, CD3, CD20)
- Existing multi-class classification workflow is unaffected

---

### Phase 2 ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â Composite Classification Builder

**Goal:** Allow users to define composite population rules from trained binary classifiers (e.g. `CD4+:CD3+:CD20-`) and apply them to write QuPath PathClasses on cells.

**Depends on:** Phase 1 (Binary Classifier Infrastructure)

**Deliverables:**
- `CompositeClassificationRule` ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â stores a list of `(markerName, polarity)` pairs; serialisable to JSON; named by user (e.g. "CD4 T cell")
- `CompositeClassifier` ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â loads N binary `ClassifierState`s, applies 50% hard threshold per classifier, ANDs results, writes composite `PathClass` (e.g. `CD4+:CD3+:CD20-`) to each `PathDetectionObject` via `Platform.runLater()`
- Composite builder dialog ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â shows all trained binary classifiers as rows with `+` / `-` / `ignore` toggles; user names the composite; apply button runs `CompositeClassifier`
- Composite rule persistence ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â named rules saved to `<project>/celltune/composite-rules.json`
- Batch composite classification ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â apply a composite rule across multiple project images (reuses `ImageSelectionPane`)
- Menu item: *Extensions > CellTune > Build Composite Classification...*

**UAT:**
- User with trained CD4, CD3, CD20 binary classifiers can define rule `CD4+:CD3+:CD20-`, apply it, and see cells coloured by that PathClass in the viewer
- Cells not matching any rule retain their previous PathClass or are assigned `Unclassified`
- Composite result appears in Cell Table CSV and AnnData exports
- Named composite rules persist across QuPath restart
- Applying to batch images works when all constituent binary classifiers are trained

**Plans:** 3 plans

Plans:
- [x] 02-01-PLAN.md Ã¢â‚¬â€ CompositeClassifier (inference engine) + ProjectStateManager composite config methods
- [x] 02-02-PLAN.md Ã¢â‚¬â€ CompositeClassificationDialog (modal UI: checkbox list, Apply, Batch, progress)
- [x] 02-03-PLAN.md Ã¢â‚¬â€ CellTuneExtension wiring (menu item + export shortcut + human verify checkpoint)

---

### Phase 3 - Composite Rule Contract and Persistence (Gap Closure)

**Goal:** Close milestone gaps for structured composite rules by adding explicit marker polarity contracts and named rule persistence.

**Depends on:** Phase 2 (Composite Classification Builder)

**Requirements:** COMP-01, COMP-04

**Gap Closure:** Closes audit gaps for missing marker-polarity rule model, missing named rule persistence, and broken save/reload composite flow.

**Deliverables:**
- Composite rule domain model (marker plus polarity tuple list, named rule metadata)
- Project persistence for named composite rules at <project>/celltune/composite-rules.json
- Dialog/service wiring that applies a selected named rule using rule polarity values, not marker-only defaults
- Summary and verification evidence for rule create/save/load/apply workflow

**Plans:** 2 plans

Plans:
- [x] 03-01-PLAN.md - Composite rule contract + composite-rules persistence APIs
- [x] 03-02-PLAN.md - Rule-driven dialog/classifier wiring + human verify checkpoint

---

### Phase 4 - Composite Rule Builder UX and Verification Hardening (Gap Closure)

**Goal:** Close milestone UX and evidence gaps by adding + / - / ignore controls, user naming flow, and complete verification artifacts for composite functionality.

**Depends on:** Phase 3 (Composite Rule Contract and Persistence)

**Requirements:** COMP-02

**Gap Closure:** Closes audit gaps for missing polarity UI controls and missing phase-level verification artifacts for composite flows.

**Deliverables:**
- Composite builder UI rows with explicit + / - / ignore controls per marker
- User-entered composite rule name input and validation
- End-to-end verification artifact for Phase 2-4 composite flows (single image, batch image, reload rule)
- Milestone audit evidence update showing closed flow and integration gaps

**Plans:** 2 plans

Plans:
- [x] 04-01-PLAN.md - Row-based + / - / ignore controls and naming workflow in composite dialog
- [ ] 04-02-PLAN.md - Phase 4 verification artifact and human verify checkpoint results

---






