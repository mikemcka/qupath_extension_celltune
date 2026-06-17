plugins {
    // To create a shadow/fat jar that bundles non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-celltune"
    group = "io.github.qupath"
    version = "0.1.0-SNAPSHOT"
    description = "CellTune-style active learning cell classifier for QuPath"
    automaticModule = "io.github.qupath.extension.celltune"
}

dependencies {

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    // ML models — bundled into the shadow jar
    implementation("ml.dmlc:xgboost4j_2.13:2.1.4")
    shadow("ml.dmlc:xgboost4j_2.13:2.1.4")
    implementation("io.github.metarank:lightgbm4j:4.6.0-2")
    shadow("io.github.metarank:lightgbm4j:4.6.0-2")

    // SMILE — PCA / UMAP / k-means for the cell scatter plot. Bundled into the
    // shadow jar. NOTE: SMILE's PCA (Matrix.svd) and UMAP (spectral init) call
    // into native BLAS/LAPACK via org.bytedeco openblas + arpack-ng — there is
    // no pure-Java fallback in 3.1.1, so the bytedeco natives must be bundled.
    // This adds the platform native binaries (multiple OS classifiers) to the
    // jar; accepted to keep PCA/UMAP working and cross-platform portable.
    implementation("com.github.haifengl:smile-core:3.1.1")
    shadow("com.github.haifengl:smile-core:3.1.1")

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Version" to project.version
        )
    }
}
