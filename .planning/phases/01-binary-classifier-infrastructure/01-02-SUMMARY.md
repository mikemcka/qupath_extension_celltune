# Summary — Plan 01-02: Binary Classifier UI + CellTuneExtension Wiring

**Status:** Complete  
**Commit:** 78f6115  
**Date:** 2026-04-27

## What Was Built

### BinaryClassifierPanel.java (new)
- JavaFX `VBox` in `qupath.ext.celltune.ui`
- Lists registered marker names in a `ListView`
- **Create...** — `TextInputDialog` → `sanitizeMarkerName()` → fires `onRegisterMarker` callback
- **Open** — fires `onOpenMarker` callback with selected marker name; disabled when no selection
- **Delete** — confirmation dialog → fires `onDeleteMarker` callback; disabled when no selection
- **Active-mode banner** — `setActiveBinaryMarker(String)` toggles a bold blue label and "Exit Binary Mode" button
- Fully callback-driven; no direct dependency on extension state

### CellTuneExtension.java (extended — new fields + 3 methods + 1 menu item)

**New fields:**
- `binaryRegistry` — `LinkedHashMap<String, String>` — in-memory copy of registry
- `activeBinaryMarker` — null when in multi-class mode
- `preBinaryLabelStore`, `preBinaryClassifier` — saved state for restore on exit

**New methods:**
- `showBinaryClassifiers(QuPathGUI)` — loads registry, creates `BinaryClassifierPanel`, wires callbacks, shows modal stage
- `enterBinaryMode(QuPathGUI, String)` — saves current state, loads binary labels/classifier, calls `syncPanelState()`, expands docked panel
- `exitBinaryMode(QuPathGUI)` — saves binary labels, restores multi-class state, calls `syncPanelState()`; no-op when `activeBinaryMarker == null`

**Modified:**
- `setOnClassifierChanged` callback extended to auto-save binary state via `ProjectStateManager.saveBinaryState()` when `activeBinaryMarker != null && cls.isTrained()`
- `addMenuItems()` — "Binary Classifiers..." item added after "Classify..." (before features/normalize group)

## Verification
- `./gradlew shadowJar` — BUILD SUCCESSFUL (0 errors)
- Multi-class workflow fully unaffected — all binary fields are null by default
- `exitBinaryMode()` is a no-op when `activeBinaryMarker == null`
- `toClassifierState(String name)` correct API used (takes name argument)
- `toLabelStore()` correct method name used in `loadBinaryLabels()`
