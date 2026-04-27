# Testing Patterns

**Analysis Date:** 2026-04-27

## Test Framework

**Runner:**
- JUnit 5 (configured via `libs.junit` version catalog alias in `build.gradle.kts`)
- Config: none beyond `build.gradle.kts` — no `junit-platform.properties`

**Run Commands:**
```bash
./gradlew test              # Run all tests
./gradlew test --info       # Verbose output
./gradlew test --tests "qupath.ext.celltune.*"  # Run specific package
```

## Test Setup in build.gradle.kts

```kotlin
dependencies {
    testImplementation(libs.bundles.qupath)   // QuPath runtime on test classpath
    testImplementation(libs.junit)            // JUnit 5
}
```

The `libs.bundles.qupath` bundle pulls in the full QuPath library stack (the same jars used by `shadow`). This means tests can instantiate QuPath model objects (`PathObject`, `PathClass`, `LabelStore`) without a running QuPath instance — as long as they don't touch the GUI layer (`QuPathGUI`, `QuPathExtension.installExtension()`).

## Existing Test Files

**No test directory exists.** `src/test/` has not been created.

```
src/
├── main/       ← all production code
└── (no test/)  ← nothing here yet
```

There are zero existing tests. All test coverage gaps described below are unverified.

## What Is Tested vs Untested

| Component | Status | Notes |
|-----------|--------|-------|
| `LabelStore` | **Untested** | Pure Java, no QuPath deps — highest priority for unit tests |
| `FeatureNormalizer` | **Untested** | Pure Java — `apply()`, `toTransformMap()`, `fromTransformMap()` |
| `CellFeatureExtractor` | **Untested** | Requires `PathObject` stubs but otherwise pure Java |
| `DualModelClassifier.trainAndPredict` | **Untested** | Requires XGBoost/LightGBM native libs; integration test |
| `DualModelClassifier.computeFeatureImportance` | **Untested** | Same as above |
| `ProjectStateManager` | **Untested** | File I/O — testable with temp directories |
| `GroundTruthIO` | **Untested** | CSV parsing — testable with file fixtures |
| `UncertaintySampler` | **Untested** | Pure Java sampling logic |
| `HyperparameterTuner` | **Untested** | Requires ML runtime |
| `ReviewController` | **Untested** | Requires QuPath objects + FX thread |
| All UI classes (`ReviewToolbar`, `ClassificationPanel`, etc.) | **Untested** | Require JavaFX toolkit |

## Testing Challenges Specific to QuPath Extensions

### 1. No Running QuPath Instance in Tests

`QuPathGUI.getInstance()` returns `null` in a headless test environment. Tests must not call `installExtension()` or any method that dereferences `QuPathGUI`.

**Workaround:** Test logic layers independently of the UI entry point. `DualModelClassifier`, `LabelStore`, `FeatureNormalizer`, and `ProjectStateManager` are all constructable without a `QuPathGUI`.

### 2. JavaFX Toolkit Not Initialized

Any test that instantiates JavaFX nodes (`Button`, `HBox`, etc.) will throw `IllegalStateException: Toolkit not initialized` unless the test bootstraps JavaFX.

**Workaround for pure logic tests:** Avoid instantiating any JavaFX class. Keep domain model (`qupath.ext.celltune.model.*`) and classifier (`qupath.ext.celltune.classifier.*`) tests completely free of JavaFX imports.

**If JavaFX UI tests are needed:** Use `TestFX` or the `javafx.application.Platform.startup(() -> {})` idiom, but this adds significant complexity. For now, UI tests are out of scope.

### 3. `Platform.runLater()` in `DualModelClassifier`

`DualModelClassifier.trainAndPredict()` calls `Platform.runLater()` to apply `setPathClass()` on the FX thread. In unit tests without a running FX thread, these callbacks are silently queued and never executed.

**Workaround:** In integration tests for `trainAndPredict()`, start the JavaFX platform or mock the `Platform.runLater()` call. Alternatively, test the pure-Java path (data assembly, feature extraction, prediction matrix shape) and verify `setPathClass` side-effects separately.

### 4. Native Library Dependencies (XGBoost, LightGBM)

`XGBoostModel` and `LightGBMModel` depend on native JNI libraries bundled via the shadow jar. In a standard `./gradlew test` run the shadow jar is not used — instead the raw JARs from `implementation` scope are on the classpath. The native libraries should still load because `xgboost4j-gpu_2.13` and `lightgbm4j` bundle their native `.so`/`.dll` files inside the JAR.

**If native loading fails in CI:** Set `-Djava.library.path` or verify that the platform architecture matches the packaged native binaries (CUDA GPU variant may require CUDA drivers).

### 5. QuPath `ProjectImageEntry` is an Interface

`ProjectStateManager` takes `ProjectImageEntry<BufferedImage>` to read/write metadata. In unit tests, mock with a simple `Map`-backed fake or use Mockito:

```java
@Mock ProjectImageEntry<BufferedImage> mockEntry;
when(mockEntry.getImageName()).thenReturn("test-image.tif");
when(mockEntry.getMetadataValue(anyString())).thenReturn(null);
```

## Recommended Approach for Testing ML Model Wrappers

### Unit Tests for Pure Java Layers (Start Here)

These have no QuPath GUI or JavaFX dependencies and are highest-priority:

**`LabelStore` tests:**
```java
@Test void testSetAndGetLabel() {
    LabelStore store = new LabelStore("test");
    store.setLabel("cell-1", "CD4T");
    assertEquals("CD4T", store.getLabel("cell-1"));
    assertEquals(1, store.size());
}

@Test void testGetClassCounts() {
    LabelStore store = new LabelStore("test");
    store.setLabel("a", "CD4T"); store.setLabel("b", "CD4T"); store.setLabel("c", "Bcell");
    assertEquals(2L, store.getClassCounts().get("CD4T"));
    assertEquals(1L, store.getClassCounts().get("Bcell"));
}
```

**`FeatureNormalizer` tests:**
```java
@Test void testArcsinhTransform() {
    FeatureNormalizer norm = new FeatureNormalizer();
    norm.setTransform("CD3", FeatureNormalizer.Transform.ARCSINH);
    norm.setArcsinhCofactor(5.0);
    float result = norm.apply("CD3", 5.0f);
    assertEquals((float) Math.log(2.0), result, 1e-4f); // arcsinh(5/5) = arcsinh(1) ≈ ln(2)
}

@Test void testSerializeRoundTrip() {
    FeatureNormalizer norm = new FeatureNormalizer();
    norm.setTransform("CD3", FeatureNormalizer.Transform.SQRT);
    Map<String, String> map = norm.toTransformMap();
    FeatureNormalizer restored = new FeatureNormalizer();
    restored.fromTransformMap(map);
    assertEquals(FeatureNormalizer.Transform.SQRT, restored.getTransform("CD3"));
}
```

### Integration Tests for ML Model Wrappers

For `XGBoostModel` and `LightGBMModel` the recommended pattern is:

1. **Generate synthetic training data** — small feature matrix (e.g. 100 cells × 10 features, 3 classes)
2. **Train the model** — verify no exception thrown
3. **Predict** — verify output shape is `[nSamples][nClasses]`, rows sum to ~1.0
4. **Serialize / deserialize** — verify predictions from deserialized model match original
5. **SHAP** (XGBoost only in `computeFeatureImportance`) — verify output shape `[nClasses][nFeatures]`, values ≥ 0

```java
@Test void xgboostTrainAndPredict() throws Exception {
    int nSamples = 60, nFeatures = 5, nClasses = 3;
    float[] data   = generateSyntheticData(nSamples, nFeatures, nClasses);
    float[] labels = generateSyntheticLabels(nSamples, nClasses);

    XGBoostModel model = new XGBoostModel();
    model.train(data, labels, nSamples, nFeatures,
            List.of("A","B","C"), List.of("f0","f1","f2","f3","f4"),
            50, 4, 0.1f, 0.8f);

    assertTrue(model.isTrained());

    float[][] probs = model.predictProba(data, nSamples, nFeatures);
    assertEquals(nSamples, probs.length);
    for (float[] row : probs) {
        float sum = 0; for (float p : row) sum += p;
        assertEquals(1.0f, sum, 0.01f); // probabilities sum to 1
    }
}

@Test void xgboostSerializeRoundTrip() throws Exception {
    // ... train, toBytes(), new XGBoostModel(), loadFromBytes(), predictProba()
    // assert predictions are identical before and after serialization
}
```

### Testing `DualModelClassifier` Column Ordering Contract

Critical regression test — ensures the feature count mismatch guard works:

```java
@Test void predictOnlyThrowsOnFeatureMismatch() throws Exception {
    // Train with 5 features, then try to predict with 6 — should throw
    DualModelClassifier cls = new DualModelClassifier();
    // ... train with extractor having 5 features ...

    CellFeatureExtractor wrongExtractor = new CellFeatureExtractor(sixFeatureNames);
    assertThrows(IllegalStateException.class,
            () -> cls.predictOnly(cells, wrongExtractor, null));
}
```

### Testing `ProjectStateManager` I/O

Use `@TempDir` to avoid filesystem side-effects:

```java
@Test void saveAndLoadLabels(@TempDir Path tempDir) throws Exception {
    // Create a mock ProjectImageEntry backed by a Map in tempDir
    // Save labels, reload, verify round-trip fidelity
}
```

### What NOT to Test

- `CellTuneExtension.installExtension()` — requires a live `QuPathGUI`
- Any class in `qupath.ext.celltune.ui.*` — requires JavaFX Toolkit
- `ReviewController` — tightly coupled to QuPath image display
- GPU-specific code paths — CI typically has no CUDA drivers

---

*Testing analysis: 2026-04-27*
