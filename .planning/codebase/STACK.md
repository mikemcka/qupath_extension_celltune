# Technology Stack

**Analysis Date:** 2026-04-27

## Languages

**Primary:**
- Java 21 — all extension source code (required by QuPath 0.7.0 convention plugin; no explicit override in `build.gradle.kts`, version mandated by `qupath-conventions`)

**Build DSL:**
- Kotlin DSL — `build.gradle.kts`, `settings.gradle.kts`

## Runtime

**Environment:**
- JVM (Java 21+) — runs inside the QuPath host application process
- Native libraries for ML are loaded at runtime via JNI: XGBoost4J bundles CUDA/CPU shared libraries; LightGBM4J bundles the LightGBM native library

**Package Manager:**
- Gradle 9.2.1 (via Gradle Wrapper — `gradle/wrapper/gradle-wrapper.properties`)
- Lockfile: none (no dependency locking configured)

## Frameworks

**Core:**
- QuPath 0.7.0 — host application; provides `QuPathGUI`, `QuPathExtension`, `PathObject`, `Project`, JavaFX scene graph, `AnalysisTabPane`; version pinned in `settings.gradle.kts` via `qupath { version = "0.7.0" }`

**UI:**
- JavaFX (bundled with QuPath 0.7.0) — all UI components; `ClassificationPanel extends VBox`, `TitledPane`, `Tab`, `MenuItem`

**Testing:**
- JUnit — referenced as `libs.junit` from the version catalog provided by `qupath-extension-settings`; `testImplementation` scope with `libs.bundles.qupath`

**Build/Dev:**
- Shadow (Fat JAR): `com.gradleup.shadow:8.3.5` — bundles ML dependencies into a single deployable JAR
- QuPath extension conventions: `qupath-conventions` plugin (provided by `io.github.qupath.qupath-extension-settings:0.2.1`) — sets Java toolchain, manifest, extension metadata
- Foojay toolchain resolver: `org.gradle.toolchains.foojay-resolver-convention:0.9.0` — automatic JDK provisioning

## Key Dependencies

**ML — XGBoost:**
- `ml.dmlc:xgboost4j-gpu_2.13:2.1.4` — XGBoost4J, GPU variant with Scala 2.13 ABI suffix
  - Scope: `implementation` + `shadow` (bundled into fat JAR)
  - Includes CUDA-enabled native shared library; falls back to CPU at runtime if CUDA probe fails
  - API surface used: `ml.dmlc.xgboost4j.java.{Booster, DMatrix, XGBoost, XGBoostError}`

**ML — LightGBM:**
- `io.github.metarank:lightgbm4j:4.6.0-2` — JVM wrapper around LightGBM native library
  - Scope: `implementation` + `shadow` (bundled into fat JAR)
  - API surface used: `io.github.metarank.lightgbm4j.{LGBMBooster, LGBMDataset}`, `com.microsoft.ml.lightgbm.PredictionType`

**QuPath Core (provided at runtime):**
- `libs.bundles.qupath` — QuPath core, GUI, image analysis APIs; `shadow` scope only (not re-bundled)
- `libs.bundles.logging` — SLF4J + Logback; `shadow` scope only
- `libs.qupath.fxtras` — QuPath JavaFX utilities (`qupath.fx.dialogs.Dialogs`, `PropertyItemBuilder`); `shadow` scope only

**Serialization:**
- Gson — `GsonBuilder().setPrettyPrinting().create()` used in `ProjectStateManager`; version managed by version catalog (transitive through QuPath or qupath-fxtras)

## Version Lock Considerations

- **QuPath version is the primary constraint.** It must be `0.7.0` exactly (`settings.gradle.kts`). Bumping it requires re-testing all `qupath.lib.*` and `qupath.fx.*` API calls.
- **XGBoost4J `2.1.4` GPU variant** — the `_2.13` suffix means Scala 2.13 ABI; do not swap to a different Scala ABI or the native library will not load. The GPU variant includes both CUDA and CPU codepaths; a CPU-only variant (`xgboost4j_2.13`) exists but the GPU artifact is used here to enable optional CUDA acceleration.
- **LightGBM4J `4.6.0-2`** — the `-2` suffix is the metarank re-packaging revision. Do not confuse with the upstream `4.6.0` release. Native binary is bundled inside the JAR; no separate install required.
- **Shadow plugin `8.3.5`** — uses `com.gradleup.shadow` (maintained fork of `com.github.johnrengelman.shadow`). Do not substitute the old johnrengelman artifact; the group ID changed.
- **Gradle 9.2.1** — requires Java 21 for the daemon. Compatible with `foojay-resolver-convention:0.9.0`.

## GPU/CPU Fallback Behavior

**XGBoost (in `XGBoostModel.java`):**
1. Before full training, a tiny 2-sample probe run is attempted with `device=cuda, tree_method=hist`.
2. If the probe `XGBoost.train()` call succeeds, real training uses `device=cuda`.
3. If the probe throws `XGBoostError`, the catch block sets `device=cpu, tree_method=hist` and trains on CPU.
4. `XGBoostModel.getLastDevice()` returns `"GPU (CUDA)"` or `"CPU"` for logging.

**LightGBM (in `LightGBMModel.java`):**
1. First training attempt appends `device_type=gpu` to the parameter string.
2. If `LGBMBooster.create()` or `updateOneIter()` throws any `Exception`, the booster is closed and re-created with CPU-only params.
3. `LightGBMModel.getLastDevice()` returns `"GPU"` or `"CPU"`.

## Configuration

**Extension Metadata (`build.gradle.kts`):**
```
name    = "qupath-extension-celltune"
group   = "io.github.qupath"
version = "0.1.0-SNAPSHOT"
automaticModule = "io.github.qupath.extension.celltune"
```

**Build:**
- `build.gradle.kts` — single-module Gradle Kotlin DSL project
- `settings.gradle.kts` — QuPath version, plugin management repos (Gradle Portal + SciJava Maven)
- No `gradle.properties` or `libs.versions.toml` present; version catalog provided entirely by the `qupath-extension-settings` plugin

## Platform Requirements

**Development:**
- Java 21 JDK (auto-provisioned via foojay toolchain resolver)
- CUDA toolkit optional (GPU training only; CPU fallback is automatic)

**Production / Deployment:**
- QuPath 0.7.0 desktop application (provides JavaFX, QuPath core libraries at runtime)
- Extension deployed as a shadow JAR dropped into QuPath's `extensions/` directory

---

*Stack analysis: 2026-04-27*
