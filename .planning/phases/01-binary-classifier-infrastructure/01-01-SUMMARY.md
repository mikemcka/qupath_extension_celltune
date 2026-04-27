# Summary — Plan 01-01: Binary Classifier Data Layer

**Status:** Complete  
**Commit:** c248293  
**Date:** 2026-04-27

## What Was Built

### BinaryClassifierRegistry.java (new)
- Utility class in `qupath.ext.celltune.io`
- `sanitizeMarkerName(String)` — strips all chars except `[A-Za-z0-9._-]`, rejects blank/leading-dot/leading-dash; prevents path traversal (T-01-01)
- `load(Project<?>)` — reads `binary-registry.json` into a `LinkedHashMap<String, String>`; returns empty map if file absent
- `save(Project<?>, Map<String, String>)` — writes registry to disk via Gson
- `register(Project<?>, Map<String, String>, String)` — sanitizes, adds entry, saves; returns sanitized name
- `remove(Project<?>, Map<String, String>, String)` — removes entry, saves, deletes the state file

### ProjectStateManager.java (extended — 4 new methods + 1 private helper)
- `getBinaryDir(Project<?>)` (private) — resolves and creates `<celltune>/binary/`
- `saveBinaryState(...)` — saves full trained state to `binary/<marker>.json` reusing `SavedState`
- `loadBinaryState(...)` — loads state, returns null if file absent
- `saveBinaryLabels(...)` — saves labels only, preserving existing model bytes (pre-training use)
- `loadBinaryLabels(...)` — loads LabelStore; returns empty store if no file exists; calls `toLabelStore()`

## Verification
- `./gradlew compileJava` — BUILD SUCCESSFUL (0 errors, 0 warnings)
- No existing methods modified — purely additive
- `sanitizeMarkerName("../../etc/passwd")` → `".._.._etc_passwd"` starts with `.` → `IllegalArgumentException`
- `sanitizeMarkerName("CD4")` → `"CD4"` unchanged
- `sanitizeMarkerName("CD4+")` → `"CD4_"` (+ replaced with _)
