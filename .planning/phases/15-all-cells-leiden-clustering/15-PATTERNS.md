# Phase 15: All-Cells Leiden Clustering (True-Scanpy) - Pattern Map

**Mapped:** 2026-07-06
**Files analyzed:** 7 (1 build file, 3 modified main classes, 1 new main class, 2 modified/existing test files)
**Analogs found:** 7 / 7 (all resolved to in-repo analogs; one file — the new ANN wrapper — has no direct in-repo functional analog, but a strong stylistic analog)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `build.gradle.kts` | config | batch (dependency resolution) | `build.gradle.kts:42-47` (CWTS `implementation`+`shadow` block) | exact |
| `src/main/java/qupath/ext/celltune/model/LeidenModel.java` (modify: ANN routing + recall gate + primitive-array SNN/Jaccard rewrite) | service/model (pure algorithm core) | transform / batch | itself — `featureKnn`/`nearestForRow` (lines 52-120) and `transferLabels`/`nearestReferenceIndices` (lines 340-417) | exact (self-extension) |
| `src/main/java/qupath/ext/celltune/model/HnswKnnIndex.java` (new — suggested name) | utility (ANN index wrapper) | transform | no functional analog in-repo; stylistic analog = `LeidenModel`'s pure-static-array convention | role-match (style only) |
| `src/main/java/qupath/ext/celltune/model/CohortClusterModel.java` (modify: new two-pass all-cells driver) | service (streaming driver) | batch / CRUD (read-modify-write per image) | itself — `sample` (lines 87-161, pass-1 shape) + `writeClusterAcrossProject`/`writeClusterAcrossProjectLeiden` (lines 281-349, 359-466, pass-2 shape) | exact (self-extension) |
| `src/main/java/qupath/ext/celltune/ui/ScatterPlotView.java` (modify: cohort-mode radio pair, cancel, soft-ceiling confirm, post-run resync) | UI component | request-response / event-driven | itself — `methodCombo` show/hide binding (lines 292-346), scope `ToggleGroup` (lines 396-424), `writeClusterMeasurementAcrossProject` (lines 1558-1665), `activateClusterMapper` (lines 1696-1715) | exact (self-extension) |
| `src/test/java/qupath/ext/celltune/model/LeidenModelTest.java` (modify: HNSW-vs-exact, recall-gate, all-cells label-count tests) | test | transform | itself — existing `featureKnn`/`cluster`/`transferLabels` synthetic-cloud tests (lines 26-231) | exact (self-extension) |
| `src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java` (modify: UUID write-back reorder test, pooling test) | test | CRUD / event-driven | itself (lines 15-78, pure-array tests) + `AnnotationLabelCollectorTest.java` (lines 22-124, real `PathObjectHierarchy`/`PathObjects` construction pattern) | role-match |

## Pattern Assignments

### `build.gradle.kts`

**Analog:** the existing CWTS dependency block, `build.gradle.kts:42-53`

**Exact pattern to copy** (lines 42-53):
```kotlin
// CWTS networkanalysis — the reference Leiden / Louvain community-detection
// implementation (Traag, van Eck & Waltman; same authors as the Python
// leidenalg that scanpy/scimap use). Smile has no graph clustering. Used for
// graph-based phenotype clustering in the scatter-plot workflow. Bundled.
implementation("nl.cwts:networkanalysis:1.3.0")
shadow("nl.cwts:networkanalysis:1.3.0")

// For testing
testImplementation(libs.bundles.qupath)
testImplementation(libs.junit)
testRuntimeOnly("org.junit.platform:junit-platform-launcher")
```

**New addition** (insert alongside, same `implementation` + `shadow` pairing — RESEARCH.md's pinned version):
```kotlin
// HNSW — approximate-NN kNN graph construction for Leiden at cohort scale
// (tens of millions of cells). Pure Java, no native binaries (unlike Smile's
// OpenBLAS/ARPACK bundling above) — see .planning/phases/15.../15-RESEARCH.md
// "Standard Stack" for the library evaluation.
implementation("com.github.jelmerk:hnswlib-core:1.2.1")
shadow("com.github.jelmerk:hnswlib-core:1.2.1")
```
Do not touch the `spotbugs`/`spotless` blocks (lines 63-99) — unrelated.

---

### `src/main/java/qupath/ext/celltune/model/LeidenModel.java`

**Analog:** itself — extend in place; three existing regions are the templates.

**Imports pattern** (lines 1-12, keep this exact style — no wildcard imports, alphabetical-ish grouping):
```java
package qupath.ext.celltune.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import nl.cwts.networkanalysis.Clustering;
import nl.cwts.networkanalysis.LeidenAlgorithm;
import nl.cwts.networkanalysis.Network;
import nl.cwts.util.LargeDoubleArray;
import nl.cwts.util.LargeIntArray;
```
New ANN-related imports (`com.github.jelmerk.hnswlib.core.*`, `com.github.jelmerk.hnswlib.core.hnsw.*`) either go here directly, or — if the wrapper class approach is chosen (recommended) — only in the new `HnswKnnIndex.java`, keeping `LeidenModel.java` itself free of the third-party ANN import surface except for calling the wrapper.

**"Build once, query every point" parallel-loop pattern to mirror for ANN kNN** (lines 52-70 — this is the shape `hnswKnn(...)` must follow, just swapping the brute-force scan in `nearestForRow` for `index.findNearest(rows[i], k)`):
```java
public static int[][] featureKnn(double[][] rows, int k) {
    int n = rows.length;
    int[][] out = new int[n][];
    if (n == 0 || k < 1) {
        for (int i = 0; i < n; i++) {
            out[i] = new int[0];
        }
        return out;
    }
    int keep = Math.min(k, n - 1);
    if (keep <= 0) {
        for (int i = 0; i < n; i++) {
            out[i] = new int[0];
        }
        return out;
    }
    IntStream.range(0, n).parallel().forEach(i -> out[i] = nearestForRow(rows, i, keep));
    return out;
}
```
Javadoc convention to preserve (lines 39-51): document Big-O, parallelism, and byte-for-byte ordering guarantees exactly as `featureKnn`'s javadoc does — the recall gate and downstream tests depend on documented ordering (distance asc, index asc tie-break via `worse(...)`, lines 127-129).

**"Small query set vs large reference set, brute force, parallel" shape — reuse directly for the recall gate's exact reference** (lines 340-359, 377-417 — `transferLabels`/`nearestReferenceIndices`; RESEARCH.md explicitly flags this as adapt-don't-rewrite):
```java
public static int[] transferLabels(double[][] query, double[][] reference, int[] refLabels, int k, int nClusters) {
    int nq = query.length;
    int[] assigned = new int[nq];
    int nRef = reference.length;
    if (nRef == 0) {
        return assigned;
    }
    int keep = Math.max(1, Math.min(k, nRef));
    int histSize = Math.max(1, nClusters);
    IntStream.range(0, nq)
            .parallel()
            .forEach(qi -> assigned[qi] = voteLabel(query[qi], reference, refLabels, keep, histSize));
    return assigned;
}
```
For the recall gate, extract a sibling of `nearestReferenceIndices` (lines 383-417) that returns **indices only** (no vote/label step) for a capped sample of query rows against the full pooled reference — this is `O(sample × n)`, matching D-07's cost bound. Do not call `featureKnn` (all-pairs `O(n²)`) for the recall check (Pitfall 3 in RESEARCH.md).

**Bounded max-heap selection primitive to reuse verbatim** (lines 79-172 — `nearestForRow`, `worse`, `siftUp`, `siftDown`, `swap`): this is the no-boxing, O(n log k) selection machinery both the exact recall-gate reference and (if a fallback NN-descent path is ever needed per D-02) any brute-force step should keep using; do not reintroduce a boxed `Integer[]`/full-sort approach anywhere in this phase.

**CWTS network construction and normalization — unchanged downstream consumer** (lines 209-247, `cluster(...)`): the ANN-sourced adjacency list feeds this exact method signature (`int[][] neighbors` → `buildJaccardWeightedNetwork` → `createNormalizedNetworkUsingAssociationStrength` → `LeidenAlgorithm.findClustering`). Do not alter the association-strength normalization call (lines 220-228 comment) — RESEARCH.md's "Code Examples" section explicitly says do not re-derive it.

**Primitive-array rewrite target — this is what must change for cohort scale** (lines 259-324, `buildJaccardWeightedNetwork`/`buildClosedNeighborSets`/`jaccard`):
```java
private static Network buildJaccardWeightedNetwork(int n, int[][] neighbors) {
    Set<Integer>[] closedSets = buildClosedNeighborSets(n, neighbors);
    Set<Long> seenEdges = new HashSet<>();
    LargeIntArray from = new LargeIntArray(0);
    LargeIntArray to = new LargeIntArray(0);
    LargeDoubleArray weights = new LargeDoubleArray(0);
    for (int i = 0; i < n; i++) {
        for (int j : neighbors[i]) {
            int a = Math.min(i, j);
            int b = Math.max(i, j);
            if (a == b) continue;
            long key = (((long) a) << 32) | (b & 0xffffffffL);
            if (!seenEdges.add(key)) continue;
            double w = jaccard(closedSets[a], closedSets[b]);
            from.append(a); to.append(b); weights.append(w);
        }
    }
    return new Network(n, true, new LargeIntArray[] {from, to}, weights, false, true);
}
```
Per RESEARCH.md Pitfall 2, this `HashSet<Integer>[]`/`HashSet<Long>` shape must be rewritten with sorted primitive `int[]` neighbor arrays + merge-based intersection counting for the all-cells scale path (keep the current implementation for the single-image / bounded-sample path if the planner chooses not to unify — but the `Network(n, true, ..., false, true)` constructor call signature and the association-strength-normalization contract must stay identical either way).

**Error handling / edge cases convention** (lines 211-216, `n==0`/`n==1` guards in `cluster`; lines 344-346, `nRef==0` guard in `transferLabels`): every new method (ANN builder, recall gate) must guard `n==0`/degenerate inputs the same defensive way — return empty/neutral results, never throw on empty input; DO throw a dedicated exception only for the recall-gate abort path (new — see below), mirroring how this file otherwise never throws.

**New exception type needed** (no existing analog — this file has no custom exceptions today): add a small unchecked exception, e.g. `AnnRecallException extends RuntimeException`, thrown only when recall stays `< 0.95` after the escalation cap (D-08). Follow the project's general convention of small, purpose-built exception types (see "No Analog Found" section).

---

### `src/main/java/qupath/ext/celltune/model/HnswKnnIndex.java` (new)

**No functional analog in this codebase** — this is genuinely new capability (the ANN index itself). Follow `LeidenModel`'s **purity convention** as the style analog: no QuPath/JavaFX types, static or small-instance pure-array-friendly API, unit-testable on synthetic clouds.

**Required behavior per RESEARCH.md "API fit detail" and Common Pitfall 1** (ANN determinism):
- Build via `HnswIndex.newBuilder(dimensions, DistanceFunctions.DOUBLE_EUCLIDEAN_DISTANCE, maxItemCount).withM(m).withEf(ef).withEfConstruction(efConstruction).build()`.
- Query via `index.findNearest(vector, k)` in a per-row parallel loop shaped exactly like `LeidenModel.featureKnn`'s `IntStream.range(0, n).parallel().forEach(...)` (line 68) — jelmerk has no single "build the whole graph" call.
- Escalate recall via `index.setEf(newEf)` (query-time only, no rebuild) per D-08.
- When the reproducibility toggle is ON: build with `numThreads == 1` in a fixed pooled-row order (removes concurrent-insertion-order variance), and if bit-for-bit graph reproducibility is required, subclass `HnswIndex` to override `public int assignLevel(double lambda)` with an instance-seeded `java.util.Random` instead of the default `ThreadLocalRandom.current()` call — `assignLevel` is public and the class is non-final (verified in RESEARCH.md against `HnswIndex.java` source). This determinism decision must be explicit, not silently skipped (RESEARCH.md Pitfall 1).

---

### `src/main/java/qupath/ext/celltune/model/CohortClusterModel.java`

**Analog:** itself — `sample` for pass-1 shape, `writeClusterAcrossProject`/`writeClusterAcrossProjectLeiden` for pass-2 shape.

**Imports pattern to keep** (lines 1-19):
```java
package qupath.ext.celltune.model;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectFilter;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
```

**Pass-1 pooling shape to mirror (pool ALL cells, not a bounded sample, plus per-cell UUID)** (lines 87-161, `sample(...)`):
```java
public static SampleData sample(
        Project<BufferedImage> project, List<String> images, List<String> markers,
        int sampleCap, FeatureNormalizer normalizer, Consumer<String> log) {
    Map<String, ProjectImageEntry<BufferedImage>> byName = entriesByName(project);
    ...
    for (String name : images) {
        ProjectImageEntry<BufferedImage> entry = byName.get(name);
        if (entry == null) { log.accept("[" + name + "] not in project — skipped"); continue; }
        ImageData<BufferedImage> imageData;
        try { imageData = entry.readImageData(); }
        catch (Exception e) { log.accept("[" + name + "] could not load — skipped"); continue; }
        if (imageData == null) { log.accept("[" + name + "] could not load — skipped"); continue; }
        List<PathObject> cells = detections(imageData);
        int n = cells.size();
        if (n == 0) { log.accept("[" + name + "] no detections — skipped"); continue; }
        totalCells += n; imageCount++;
        float[] flat = extractor.extractMatrix(cells);
        ... // pool every row here (drop the sampleIndices() cap for the all-cells path)
        log.accept(String.format("[%s] sampled %,d of %,d cells", name, take, n));
    }
    ...
}
```
For the all-cells pass 1: drop the `perImage`/`sampleIndices` cap, iterate every cell, and additionally capture `cells.get(idx).getID().getMostSignificantBits()` / `getLeastSignificantBits()` per row (per RESEARCH.md Pattern 2 — use a primitive `long`-pair store, NOT `.toString()`, at this scale — this is a deliberate, phase-specific deviation from the codebase's existing `getID().toString()` convention used everywhere else, e.g. `GroundTruthIO.java:125` below). Do not retain `imageData`/hierarchy references across the loop — mirrors this method's existing "read, extract, move on" shape (no explicit `close()` call exists in the current code either; QuPath's `ProjectImageEntry.readImageData()` returns a value the GC reclaims once the loop iterates past it — do not introduce a new retention pattern here).

**Existing UUID-keyed lookup-map pattern to copy (String-key variant), from outside this file** (`GroundTruthIO.java:123-126`):
```java
Map<String, PathObject> cellById = new LinkedHashMap<>();
for (PathObject cell : cells) {
    cellById.put(cell.getID().toString(), cell);
}
```
For pass 2's UUID lookup, adapt this shape but keyed on the packed `(long, long)` pair per image (a `Map<Long, Integer>` after combining `msb`/`lsb`, or two parallel sorted primitive arrays + binary search, per RESEARCH.md's Pattern 2/Anti-Pattern note) rather than `String`/`.toString()` — avoid the ~10-20x string/object overhead at cohort scale. Flag this deviation in a code comment so it is not "corrected" back to `.toString()` in review (RESEARCH.md explicit warning).

**Pass-2 write-back shape to mirror exactly (per-image stream → z-score → label → write measurement → save → progress)** (lines 359-466, private `writeClusterAcrossProject` driver, and lines 468-498 `applyMeasurement`):
```java
private static long writeClusterAcrossProject(
        Project<BufferedImage> project, List<String> images, List<String> markers,
        double[] mean, double[] sd, String classFilter, FeatureNormalizer normalizer,
        ImageData<BufferedImage> openData, String openName,
        Consumer<String> log, DoubleConsumer progress,
        java.util.function.Function<double[][], int[]> labelsForRows) {
    ...
    long totalAssigned = 0;
    int done = 0;
    for (String name : images) {
        ProjectImageEntry<BufferedImage> entry = byName.get(name);
        if (entry == null) { log.accept("[" + name + "] not in project — skipped"); done++; progress.accept(done / (double) images.size()); continue; }
        boolean isOpen = name.equals(openName);
        ImageData<BufferedImage> imageData;
        try { imageData = isOpen ? openData : entry.readImageData(); }
        catch (Exception e) { log.accept("[" + name + "] could not load — skipped"); done++; progress.accept(done / (double) images.size()); continue; }
        ...
        applyMeasurement(imageData, cells, values, isOpen);
        try { entry.saveImageData(imageData); }
        catch (Exception e) { logger.error("Failed to save {}", name, e); log.accept("[" + name + "] save failed: " + e.getMessage()); }
        totalAssigned += assigned;
        log.accept(String.format("[%s] cluster written to %,d of %,d cells", name, assigned, n));
        done++;
        progress.accept(done / (double) images.size());
    }
    return totalAssigned;
}
```
For the all-cells pass 2: replace `labelsForRows.apply(...)` (a live k-means/transfer call) with a per-image UUID → label lookup built from the pooled `(msb,lsb)→label` map filtered to this image's rows, and add a **cancellation check** at the top of each per-image loop iteration (new — no existing analog; see "No Analog Found" for cancellation).

**FX-thread marshalling pattern to reuse verbatim** (lines 469-498, `applyMeasurement`) — do not write a new marshalling helper; call this exact method (or its Leiden-labels sibling) for the open image in pass 2:
```java
private static void applyMeasurement(
        ImageData<BufferedImage> imageData, List<PathObject> cells, double[] values, boolean isOpen) {
    Runnable apply = () -> {
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).getMeasurementList().put(CLUSTER_MEASUREMENT, values[i]);
        }
        imageData.getHierarchy().fireHierarchyChangedEvent(CohortClusterModel.class);
    };
    if (isOpen) {
        if (Platform.isFxApplicationThread()) { apply.run(); }
        else {
            var latch = new java.util.concurrent.CountDownLatch(1);
            Platform.runLater(() -> { try { apply.run(); } finally { latch.countDown(); } });
            try { latch.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    } else { apply.run(); }
}
```

**Static helpers to reuse as-is** (lines 652-664, `entriesByName`, `detections`): both passes of the new all-cells driver call these unchanged.

**Docstring convention** (lines 21-37, class javadoc) — the class-level "never hold the whole cohort in memory" principle must be extended (not replaced) to describe the new all-cells two-pass mode alongside the existing sample/assign two-pass description.

---

### `src/main/java/qupath/ext/celltune/ui/ScatterPlotView.java`

**Analog:** itself — `methodCombo` visibility binding for the radio pair; scope `ToggleGroup` for building the new mode toggle; `writeClusterMeasurementAcrossProject` for the confirm+thread+progress shape; `activateClusterMapper` for post-run resync.

**Existing imports to extend, not replace** (lines 1-64) — add `javafx.scene.control.RadioButton` next to the existing `javafx.scene.control.ToggleButton`/`ToggleGroup` imports (line 27-28); no other import changes expected.

**Method-combo show/hide binding — mirror this exactly for the cohort-mode radio pair** (lines 292-346):
```java
methodCombo = new ComboBox<>();
methodCombo.getItems().addAll(ClusterMethod.KMEANS, ClusterMethod.LEIDEN);
methodCombo.setValue(ClusterMethod.KMEANS);
...
boolean methodIsLeiden = methodCombo.getValue() == ClusterMethod.LEIDEN;
kLabel.managedProperty().bind(kLabel.visibleProperty());
kSpinner.managedProperty().bind(kSpinner.visibleProperty());
...
kLabel.setVisible(!methodIsLeiden);
kSpinner.setVisible(!methodIsLeiden);
methodCombo.valueProperty().addListener((o, a, b) -> {
    boolean leiden = b == ClusterMethod.LEIDEN;
    kLabel.setVisible(!leiden);
    kSpinner.setVisible(!leiden);
    resolutionLabel.setVisible(leiden);
    resolutionSpinner.setVisible(leiden);
});
```
For D-04 (radio pair visible only in project scope AND method==LEIDEN): build a `RadioButton`/`ToggleGroup` pair ("Cluster all cells" default-selected / "Transfer from sample"), bind `managedProperty()` to `visibleProperty()` the same way, and set visibility from **both** `methodCombo.valueProperty()`'s listener (leiden-only) **and** `applyScopeOverrides()` (project-only, see below) — do not repurpose `methodCombo` itself (RESEARCH.md's explicit anti-pattern).

**Scope `ToggleGroup` construction — mirror this shape for the new mode `ToggleGroup`** (lines 396-424):
```java
ToggleGroup scopeGroup = new ToggleGroup();
imageScopeToggle = new ToggleButton("Current image");
projectScopeToggle = new ToggleButton("Project");
imageScopeToggle.setToggleGroup(scopeGroup);
projectScopeToggle.setToggleGroup(scopeGroup);
imageScopeToggle.setSelected(true);
...
scopeGroup.selectedToggleProperty().addListener((obs, old, sel) -> {
    if (sel == null && old != null) {
        old.setSelected(true); // keep exactly one selected
    }
});
imageScopeToggle.setOnAction(e -> { if (imageScopeToggle.isSelected()) switchToImageScope(); });
projectScopeToggle.setOnAction(e -> { if (projectScopeToggle.isSelected()) switchToProjectScope(); });
```
`applyScopeOverrides()` (lines 1793-1803) is the existing single place that flips visibility/labels on `scope` change — extend it (do not duplicate its logic elsewhere) to also toggle the new mode-radio-pair's visibility:
```java
private void applyScopeOverrides() {
    boolean project = scope == Scope.PROJECT;
    annotationField.setDisable(project);
    projectControls.setVisible(project);
    applyClustersBtn.setText(project ? "Assign Clusters…" : "Apply Clusters…");
    clusterOverlayBtn.setText(project ? "By cluster (all images)" : "By cluster");
    imageScopeToggle.setSelected(!project);
    projectScopeToggle.setSelected(project);
}
```

**Confirm-dialog + background-thread + progress + status-line shape — mirror this exactly for the all-cells run (soft ceiling confirm, per-phase progress, cancel)** (lines 1558-1665, `writeClusterMeasurementAcrossProject`):
```java
private void writeClusterMeasurementAcrossProject() {
    final Project<BufferedImage> project = qupath.getProject();
    if (project == null) { Dialogs.showErrorMessage("CellTune", "No project is open."); return; }
    boolean ok = Dialogs.showConfirmDialog(
            "Write cluster measurement across project",
            String.format("Assign a cluster to every cell across %d image(s), writing a non-destructive "
                    + "\"%s\" measurement, and save each image? Classifications are not changed.",
                    projectImages.size(), CLUSTER_MEASUREMENT));
    if (!ok) return;
    ...
    applying = true;
    setControlsDisabled(true);
    progress.setProgress(0);
    progress.setVisible(true);
    statusLabel.setText("Writing cluster measurement across " + images.size() + " image(s)…");
    new Thread(() -> {
        try {
            ...
            total = CohortClusterModel.writeClusterAcrossProjectLeiden(
                    project, images, markers, mean, sd, leidenRef, leidenRefLabels, LEIDEN_GRAPH_K, nClusters,
                    classFilter, normalizer, openData, openName,
                    msg -> Platform.runLater(() -> statusLabel.setText(msg)),
                    frac -> Platform.runLater(() -> progress.setProgress(frac)));
            Platform.runLater(() -> {
                QuPathViewer viewer = qupath.getViewer();
                if (viewer != null && viewer.getImageData() != null) {
                    activateClusterMapper(viewer, nClusters);
                    clusterOverlayActive = true;
                }
                clusterMeasurementStale = false;
                statusLabel.setText(String.format(
                        "Wrote cluster to %,d cell(s) across %d image(s) — saved, non-destructive.",
                        fTotal, images.size()));
            });
        } catch (Throwable t) {
            logger.error("Project cluster-measurement write failed", t);
            Platform.runLater(() -> statusLabel.setText("Cluster write failed: " + t.getMessage()));
        } finally {
            Platform.runLater(() -> {
                progress.setVisible(false);
                progress.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                setControlsDisabled(false);
                applying = false;
            });
        }
    }, "CellTune-ClusterMeasure").start();
}
```
For the all-cells action:
- Add a soft-ceiling confirm (D-10, 50M pooled cells) as an **additional** `Dialogs.showConfirmDialog(...)` before the same background-thread-launch shape, using the same `Dialogs.showConfirmDialog` call already imported (`qupath.fx.dialogs.Dialogs`, line 49).
- Reuse the same `progress`/`progressBar`/`statusLabel` triple and `msg -> Platform.runLater(...)`/`frac -> Platform.runLater(...)` lambda shapes for per-phase progress messages (D-11: "Pooling 12/40 images", "Building kNN graph…", "Running Leiden…", "Writing 12/40 images") — call `log.accept(...)` at each phase boundary in the new `CohortClusterModel` driver exactly as the existing per-image `log.accept(String.format("[%s] cluster written...", ...))` calls do (line 460).
- Reuse `activateClusterMapper(viewer, nClusters)` (see below) verbatim for the post-run resync (D-08/LEI-09) — do not write a second mapper-activation method.

**Post-run legend/overlay resync — reuse this method verbatim, unchanged** (lines 1696-1715, `activateClusterMapper`):
```java
private void activateClusterMapper(QuPathViewer viewer, int nClusters) {
    int n = Math.max(2, nClusters);
    int[] r = new int[n]; int[] g = new int[n]; int[] b = new int[n];
    for (int c = 0; c < n; c++) {
        Color col = plot.clusterColor(Math.min(c, Math.max(0, nClusters - 1)));
        r[c] = (int) Math.round(col.getRed() * 255);
        g[c] = (int) Math.round(col.getGreen() * 255);
        b[c] = (int) Math.round(col.getBlue() * 255);
    }
    ColorMaps.ColorMap cm = ColorMaps.createColorMap(CLUSTER_MEASUREMENT, r, g, b);
    MeasurementMapper mm = new MeasurementMapper(
            cm, CLUSTER_MEASUREMENT, viewer.getImageData().getHierarchy().getDetectionObjects());
    mm.setDisplayMinValue(1);
    mm.setDisplayMaxValue(Math.max(2, nClusters));
    mm.setExcludeOutsideRange(true);
    viewer.getOverlayOptions().setMeasurementMapper(mm);
    viewer.repaintEntireImage();
}
```
LEI-09 requires this to be called with the **final all-cells `nClusters`** after the all-cells write completes (not the preview subsample's `fitNClusters`) — the planner must ensure the all-cells driver returns/exposes its own community count for this call, separate from `fitNClusters` if they can diverge.

**Pre-flight "cancelled" confirm-dialog pattern (existing, but NOT true mid-run cancellation)** (lines 1839-1844, `switchToProjectScope`):
```java
List<String> chosen = pickImages(project);
if (chosen == null || chosen.isEmpty()) {
    // Cancelled: restore the toggle to whatever scope is actually active.
    applyScopeOverrides();
    return;
}
```
This and the two other "cancel" sites (lines 1168, 1397) are all pre-flight dialog-decline cancellations, not mid-batch interruption — do not mistake these for an existing cancellation mechanism (RESEARCH.md Pitfall 4 / see "No Analog Found" below for the new cancel primitive this phase must add).

---

### `src/test/java/qupath/ext/celltune/model/LeidenModelTest.java`

**Analog:** itself — extend in place with new `@Test` methods; keep the existing synthetic-cloud helper methods (`randomCloud`, `fillBlob`, `bruteForceKnn`, `purity`, `toSet`) and reuse them.

**Imports/class-doc convention to extend** (lines 1-22):
```java
package qupath.ext.celltune.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.ext.celltune.model.LeidenModel.LeidenResult;
```

**Community-recovery-by-purity test shape to mirror for the all-cells label-count/recovery test (LEI-06 acceptance)** (lines 87-113, `clusterRecoversThreeSeparatedBlobsByPurity`):
```java
@Test
void clusterRecoversThreeSeparatedBlobsByPurity() {
    Random rng = new Random(11);
    int per = 50;
    int n = per * 3;
    double[][] rows = new double[n][2];
    fillBlob(rows, rng, 0, per, 0.0, 0.0, 0.3);
    fillBlob(rows, rng, per, 2 * per, 20.0, 0.0, 0.3);
    fillBlob(rows, rng, 2 * per, 3 * per, 0.0, 20.0, 0.3);
    LeidenResult res = LeidenModel.cluster(rows, 15, 0.3, 10, 42L);
    assertTrue(res.nClusters() >= 2, "...");
    assertTrue(purity(res.labels(), 0, per) > 0.9, "Blob A not pure");
    ...
}
```
New test (all-cells, LEI-06): build a synthetic multi-blob cloud sized to represent "pooled across images" (no image concept needed at this pure-array layer — that's `CohortClusterModelTest`'s job), assert `res.labels().length == rows.length` (label count == cell count, no reference/query split) and purity per blob.

**Reproducibility test shape to mirror for the ANN-determinism note (ties to Pitfall 1, D-13)** (lines 140-152, `clusterSameSeedProducesIdenticalLabels`):
```java
@Test
void clusterSameSeedProducesIdenticalLabels() {
    Random rng = new Random(3);
    double[][] rows = new double[120][3];
    fillBlob(rows, rng, 0, 60, 0.0, 0.0, 0.4);
    fillBlob(rows, rng, 60, 120, 15.0, 15.0, 0.4);
    LeidenResult a = LeidenModel.cluster(rows, 15, 1.0, 5, 99L);
    LeidenResult b = LeidenModel.cluster(rows, 15, 1.0, 5, 99L);
    assertArrayEquals(a.labels(), b.labels(), "Identical inputs+seed must yield identical labels");
    assertEquals(a.nClusters(), b.nClusters());
}
```
New test: same shape but routed through the ANN kNN builder (single-threaded, seeded per D-13/Pitfall-1 mitigation) feeding `cluster`'s downstream stages, asserting identical labels across two runs — this is the test that will be flaky if the `assignLevel` seeding override is skipped (RESEARCH.md's explicit warning sign).

**Brute-force-vs-algorithm comparison shape to mirror for HNSW-vs-exact agreement (LEI-07)** (lines 26-40, `featureKnnMatchesBruteForceSetOnRandomCloud`, plus its `bruteForceKnn` helper at lines 252-266):
```java
@Test
void featureKnnMatchesBruteForceSetOnRandomCloud() {
    Random rng = new Random(42);
    int n = 150; int d = 5; int k = 8;
    double[][] rows = randomCloud(rng, n, d);
    int[][] actual = LeidenModel.featureKnn(rows, k);
    for (int i = 0; i < n; i++) {
        assertEquals(k, actual[i].length, "...");
        assertFalse(toSet(actual[i]).contains(i), "...");
        Set<Integer> expected = bruteForceKnn(rows, i, k);
        assertEquals(expected, toSet(actual[i]), "kNN set mismatch at index " + i);
    }
}
```
New test (HNSW-vs-exact Leiden agreement, LEI-07): run `LeidenModel.cluster` once via `featureKnn` (exact) and once via the new ANN-routed path on the same synthetic cloud, compute an ARI (Adjusted Rand Index — new small helper, no existing analog, follow the file's existing `purity(...)` helper style at lines 285-293 for a similarly self-contained static metric helper) and assert it clears the agreed tolerance.

**Recall-gate pass/escalate/abort tests** — no existing analog (novel to this phase); follow `assertDoesNotThrow`/exception-assertion conventions already used at lines 213-231 (`transferLabelsHandlesDegenerateQueryWithoutCrashing`, `transferLabelsSingleReferenceRowNeverCrashes`) for the "adequate params pass" case, and use `org.junit.jupiter.api.Assertions.assertThrows` (not yet imported in this file — add it) for the "degraded params → abort" case asserting the new `AnnRecallException` (or equivalent) is thrown and no labels are produced.

---

### `src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java`

**Analog:** itself (pure-array tests, no QuPath types today) for style; `AnnotationLabelCollectorTest.java` for the "construct a real `PathObjectHierarchy`/`PathObject` with a real `getID()`" pattern needed by the new UUID write-back test.

**Existing pure-array test style to extend** (lines 1-16, 43-70 — `sampleIndices` determinism tests):
```java
package qupath.ext.celltune.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CohortClusterModelTest {
    @Test
    void sampleIndicesIsDeterministicForAFixedSeed() {
        int[] a = CohortClusterModel.sampleIndices(50, 10, new Random(7));
        int[] b = CohortClusterModel.sampleIndices(50, 10, new Random(7));
        assertArrayEquals(a, b, "same seed → same sample");
    }
}
```

**Real-`PathObject`/`getID()` construction pattern to copy for the LEI-08 reorder test** (`AnnotationLabelCollectorTest.java:24-44`):
```java
private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

private static PathObject detectionAt() {
    ROI roi = ROIs.createRectangleROI(10, 10, 20, 20, PLANE);
    return PathObjects.createDetectionObject(roi);
}

private static PathObjectHierarchy hierarchyWith(PathObject... objects) {
    PathObjectHierarchy h = new PathObjectHierarchy();
    for (PathObject o : objects) {
        h.addObject(o);
    }
    return h;
}
```
Adapt this to build two small synthetic "images" (two `PathObjectHierarchy`/detection-list pairs, no real `Project`/`ImageData` needed if the two-pass driver's core pooling/labeling/write-back logic is exposed as a pure-array-plus-UUID helper distinct from the QuPath-I/O-heavy per-image loop — recommended architecture per RESEARCH.md's "Claude's Discretion" note on where the two-pass driver lives). The test must: (1) pool cells + `getID()` from hierarchy A then B in one order, (2) cluster, (3) re-read hierarchy B's cells in a **shuffled** order (`Collections.shuffle` or reversed list), (4) assert every cell's written `Cluster` measurement matches its pass-1 pooled-row label via UUID lookup, not position.

**Existing UUID-as-string test convention elsewhere in the test tree to be aware of (do NOT copy at this scale)** (`AnnotationLabelCollectorTest.java:54`, `:60`, `:109`, `:122` — `det.getID().toString()`): fine for these single-hierarchy tests; the new CohortClusterModel test should still be able to use `.toString()` for its own assertions/bookkeeping (test code isn't cohort-scale), even though the production write-back path itself uses the packed-long-pair form.

## Shared Patterns

### Per-image streaming + FX marshalling
**Source:** `CohortClusterModel.java` — private `writeClusterAcrossProject`/`assignAcrossProject` drivers (lines 359-466, 509-616) + `applyMeasurement`/`applyClasses` (lines 469-498, 619-648)
**Apply to:** the new two-pass all-cells driver's both passes; any per-image mutation of the *open* image's live hierarchy must go through the same `Platform.isFxApplicationThread()` check + `CountDownLatch` marshalling shown in `applyMeasurement` — do not write a second marshalling helper.

### Confirm dialog → background Thread → progress/status → try/catch/finally shape
**Source:** `ScatterPlotView.java` — `writeClusterMeasurementAcrossProject` (lines 1558-1665)
**Apply to:** the new "Write cluster measurement (all-cells)" UI action, including the D-10 soft-ceiling confirm as an extra `Dialogs.showConfirmDialog` call before the same thread-launch shape, and the D-11 progress-message phrasing pattern (`log.accept(String.format("[%s] cluster written to %,d of %,d cells", ...))`, line 460) extended with phase-level messages ("Pooling…", "Building kNN graph…", "Running Leiden…").

### Bounded max-heap kNN selection (no boxing)
**Source:** `LeidenModel.java` — `nearestForRow`/`worse`/`siftUp`/`siftDown`/`swap` (lines 79-172)
**Apply to:** the recall-gate's exact-reference computation (small query set vs full pooled reference) and any brute-force fallback path; do not reintroduce boxed `Integer[]`/full-sort selection anywhere in the new code.

### CWTS Network construction + association-strength normalization (unchanged)
**Source:** `LeidenModel.java` — `cluster` (lines 209-247), `buildJaccardWeightedNetwork` (lines 259-297)
**Apply to:** both the single-image and cohort ANN-routed paths — only the adjacency-list *source* changes (ANN `findNearest` vs brute-force scan); the `Network(n, true, new LargeIntArray[]{from,to}, weights, false, true)` constructor call and the `createNormalizedNetworkUsingAssociationStrength()` call must not be altered.

### `implementation` + `shadow` dependency bundling
**Source:** `build.gradle.kts` (lines 27-47, XGBoost/LightGBM/Smile/CWTS blocks)
**Apply to:** the new `com.github.jelmerk:hnswlib-core:1.2.1` dependency — same two-line pairing, placed in its own commented block explaining why (pure Java, no native binaries, unlike Smile above it).

## No Analog Found

Files/concerns with no close match in the codebase (planner should use RESEARCH.md patterns instead):

| File / Concern | Role | Data Flow | Reason |
|---|---|---|---|
| `HnswKnnIndex.java` (or equivalent ANN wrapper) | utility | transform | No ANN/approximate-search code exists anywhere in this codebase today; RESEARCH.md's "API fit detail" and "Common Pitfalls: Pitfall 1" sections are the primary reference. Style-only analog: `LeidenModel`'s pure-static-array convention. |
| Recall-gate abort exception (`AnnRecallException` or similar) | model (error type) | — | This codebase has no custom exception types in the `model` package today (errors are logged and swallowed, e.g. `CohortClusterModel.java:454-458`, or return neutral/empty results, e.g. `LeidenModel`'s `n==0` guards) — the recall gate is the first place in this codebase that must hard-abort a run with no output. Use a plain unchecked `RuntimeException` subclass; no existing convention to match beyond general Java-exception hygiene. |
| True mid-run cancellation (`AtomicBoolean`/cancellation token) | utility (concurrency primitive) | event-driven | RESEARCH.md "Don't Hand-Roll" + Pitfall 4: only pre-flight dialog-decline "cancellations" exist today (`ScatterPlotView.java:1168, 1397, 1839-1844`) — none interrupt an already-running background thread. Build a small `AtomicBoolean cancelled` checked at phase boundaries and each per-image iteration; do not over-engineer a generic task-cancellation framework. |
| Soft cell-count ceiling pre-scan (count detections without full pooling) | utility | transform | No existing "count-only" project scan exists; the current `sample()`/`writeClusterAcrossProject` always fully reads each `ImageData` it processes. RESEARCH.md Open Question 3 flags this as worth checking against QuPath's API (`ProjectImageEntry`/`PathObjectHierarchy` count accessors) before assuming a full extra pre-scan pass is required. |

## Metadata

**Analog search scope:** `src/main/java/qupath/ext/celltune/model/`, `src/main/java/qupath/ext/celltune/ui/`, `src/test/java/qupath/ext/celltune/model/`, `build.gradle.kts`
**Files read in full:** `LeidenModel.java` (430 lines), `CohortClusterModel.java` (707 lines), `LeidenModelTest.java` (294 lines), `CohortClusterModelTest.java` (78 lines), `AnnotationLabelCollectorTest.java` (124 lines), `build.gradle.kts` (99 lines)
**Files read in targeted sections:** `ScatterPlotView.java` (2375 lines total; read lines 1-220, 280-500, 740-920, 1540-1720, 1780-1900 — covers imports/fields, method-combo + scope-toggle construction, the Leiden-fit background thread, the cohort write action + resync, and scope-switch/confirm handling)
**Cross-codebase grep for UUID identity convention:** `getID()`/`getID().toString()` usage across `CellTuneExtension.java`, `ClassificationPanel.java`, `GroundTruthIO.java`, `ImportExport.java`, `TrainingTileExtractor.java`, `TrainingOrchestrator.java`, `DualModelClassifier.java`, `ManualLabelToolbar.java`, `ReviewController.java`, `PredictionBatcher.java`, `AnnotationLabelCollector.java`, `ScatterPlotCanvas.java` — confirms `getID()` stability-across-reads is already an established (String-keyed) idiom; this phase's packed-long-pair form is a documented, scale-motivated deviation, not a new assumption.
**Pattern extraction date:** 2026-07-06
