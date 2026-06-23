package qupath.ext.celltune.classifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;

/**
 * Unsupervised feature pruner with marker-group guardrails.
 *
 * <p>Designed to be run on the <b>labelled cell set</b> at the start of each
 * training round. The labelled set is small (typically tens to a few thousand
 * cells), so the pipeline runs in milliseconds and uses every cell — no
 * subsampling.
 *
 * <p>Pipeline:
 * <ol>
 *   <li><b>Sparsity / variance filter</b> — drop features that are non-zero in
 *       fewer than {@code minNonZeroCells} cells.</li>
 *   <li><b>Within-marker correlation removal</b> — within each marker group,
 *       keep the highest-variance feature and drop peers with |Pearson r|
 *       above the threshold. Groups are processed in parallel.</li>
 *   <li><b>Cross-marker correlation removal</b> — optional, off by default.
 *       Greedy keep-set across all surviving features.</li>
 *   <li><b>Per-group guardrail</b> — if any marker group ends up with zero
 *       surviving features the highest-variance original feature for that
 *       group is force-kept so the classifier never goes blind to a marker.</li>
 * </ol>
 *
 * <p>The "marker group" of a feature is the prefix before the first
 * {@code ": "} — matching the convention used by
 * {@code FeatureSelectionPane.discoverPrefixes}.
 */
public final class FeaturePruner {

    private static final Logger logger = LoggerFactory.getLogger(FeaturePruner.class);

    private FeaturePruner() {}

    /**
     * Pruning configuration.
     *
     * @param minNonZeroCells            drop a feature if non-zero in fewer than this many cells
     * @param withinMarkerCorrThreshold  drop within-marker peers with |r| above this threshold
     * @param crossMarkerCorrThreshold   |r| threshold for cross-marker dropping; use 1.0 to disable
     * @param keepAtLeastOnePerGroup     if true, force-keep the highest-variance feature
     *                                   for any marker group that would otherwise be empty
     */
    public record PruneOptions(
            int minNonZeroCells,
            double withinMarkerCorrThreshold,
            double crossMarkerCorrThreshold,
            boolean keepAtLeastOnePerGroup) {
        /** Defaults tuned for training-time pruning over labelled cells. */
        public static PruneOptions defaults() {
            return new PruneOptions(5, 0.95, 1.0, true);
        }
    }

    /**
     * Result of a pruning pass.
     *
     * @param keptFeatures        surviving feature names (in original order)
     * @param originalCount       size of the input feature list
     * @param droppedConstant     features removed in the sparsity stage
     * @param droppedWithinMarker features removed by within-marker correlation
     * @param droppedCrossMarker  features removed by cross-marker correlation
     * @param forceKeptMarkers    marker groups whose highest-variance feature was force-kept
     * @param markersRetained     marker groups with ≥1 surviving feature
     * @param markersLost         marker groups that ended up with zero features (guardrail off)
     * @param cellsScanned        number of cells contributing to stats
     */
    public record PruneResult(
            List<String> keptFeatures,
            int originalCount,
            int droppedConstant,
            int droppedWithinMarker,
            int droppedCrossMarker,
            List<String> forceKeptMarkers,
            List<String> markersRetained,
            List<String> markersLost,
            int cellsScanned) {}

    /**
     * Run the prune pipeline.
     *
     * @param detections   cells to base statistics on (usually the labelled set)
     * @param featureNames features to consider (in QuPath measurement-name form)
     * @param opts         configuration
     * @param progress     optional progress callback (may be {@code null})
     * @return pruning result; never {@code null}
     */
    public static PruneResult prune(
            Collection<PathObject> detections,
            List<String> featureNames,
            PruneOptions opts,
            Consumer<String> progress) {
        if (featureNames == null || featureNames.isEmpty()) {
            return new PruneResult(List.of(), 0, 0, 0, 0, List.of(), List.of(), List.of(), 0);
        }
        if (detections == null || detections.isEmpty()) {
            return new PruneResult(
                    List.copyOf(featureNames),
                    featureNames.size(),
                    0,
                    0,
                    0,
                    List.of(),
                    groupKeys(featureNames),
                    List.of(),
                    0);
        }

        final PathObject[] cells = detections.toArray(new PathObject[0]);
        final int nCells = cells.length;
        final int nFeatures = featureNames.size();
        final String[] names = featureNames.toArray(new String[0]);

        report(progress, "Pruning " + nFeatures + " features against " + nCells + " labelled cells…");

        final String[] groupOf = new String[nFeatures];
        for (int j = 0; j < nFeatures; j++) {
            groupOf[j] = extractGroup(names[j]);
        }

        // ── Stage 0: stats + standardised columns (parallel by feature) ─────
        final double[] mean = new double[nFeatures];
        final double[] std = new double[nFeatures];
        final int[] nonZero = new int[nFeatures];
        final double[][] z = new double[nFeatures][];

        IntStream.range(0, nFeatures).parallel().forEach(j -> {
            String name = names[j];
            double s = 0.0, ss = 0.0;
            int nz = 0;
            double[] raw = new double[nCells];
            for (int i = 0; i < nCells; i++) {
                double v = cells[i].getMeasurementList().get(name);
                if (Double.isNaN(v)) v = 0.0;
                raw[i] = v;
                s += v;
                ss += v * v;
                if (v != 0.0) nz++;
            }
            double m = s / nCells;
            double varJ = Math.max(0.0, ss / nCells - m * m);
            double sd = Math.sqrt(varJ);
            mean[j] = m;
            std[j] = sd;
            nonZero[j] = nz;
            if (sd > 0.0) {
                double inv = 1.0 / sd;
                double[] zj = new double[nCells];
                for (int i = 0; i < nCells; i++) zj[i] = (raw[i] - m) * inv;
                z[j] = zj;
            }
        });

        // ── Stage 1: sparsity / constant filter ─────────────────────────────
        final boolean[] kept = new boolean[nFeatures];
        int droppedConstant = 0;
        for (int j = 0; j < nFeatures; j++) {
            if (nonZero[j] >= opts.minNonZeroCells() && std[j] > 0.0) {
                kept[j] = true;
            } else {
                droppedConstant++;
            }
        }
        report(progress, "Sparsity: dropped " + droppedConstant + " near-constant features");

        // ── Stage 2: within-marker correlation (parallel by group) ──────────
        Map<String, List<Integer>> groupIndices = new LinkedHashMap<>();
        for (int j = 0; j < nFeatures; j++) {
            if (kept[j]) {
                groupIndices.computeIfAbsent(groupOf[j], k -> new ArrayList<>()).add(j);
            }
        }

        AtomicInteger droppedWithinAtom = new AtomicInteger(0);
        groupIndices.entrySet().parallelStream().forEach(entry -> {
            List<Integer> idxs = entry.getValue();
            if (idxs.size() < 2) return;
            idxs.sort((a, b) -> Double.compare(std[b], std[a]));
            List<Integer> keepSet = new ArrayList<>();
            for (int idx : idxs) {
                boolean redundant = false;
                for (int keptIdx : keepSet) {
                    double r = Math.abs(pearson(z[idx], z[keptIdx]));
                    if (r > opts.withinMarkerCorrThreshold()) {
                        redundant = true;
                        break;
                    }
                }
                if (redundant) {
                    kept[idx] = false; // disjoint indices across groups — safe
                    droppedWithinAtom.incrementAndGet();
                } else {
                    keepSet.add(idx);
                }
            }
        });
        int droppedWithin = droppedWithinAtom.get();
        report(progress, "Within-marker: dropped " + droppedWithin + " redundant features");

        // ── Stage 3: cross-marker correlation (optional, sequential greedy) ─
        int droppedCross = 0;
        if (opts.crossMarkerCorrThreshold() < 1.0) {
            List<Integer> survivors = new ArrayList<>();
            for (int j = 0; j < nFeatures; j++) if (kept[j]) survivors.add(j);
            survivors.sort((a, b) -> Double.compare(std[b], std[a]));
            List<Integer> keepSet = new ArrayList<>();
            final double thr = opts.crossMarkerCorrThreshold();
            for (int idx : survivors) {
                final int idxF = idx;
                boolean redundant;
                if (keepSet.size() < 32) {
                    redundant = false;
                    for (int keptIdx : keepSet) {
                        if (groupOf[idxF].equals(groupOf[keptIdx])) continue;
                        if (Math.abs(pearson(z[idxF], z[keptIdx])) > thr) {
                            redundant = true;
                            break;
                        }
                    }
                } else {
                    redundant = keepSet.parallelStream()
                            .anyMatch(keptIdx -> !groupOf[idxF].equals(groupOf[keptIdx])
                                    && Math.abs(pearson(z[idxF], z[keptIdx])) > thr);
                }
                if (redundant) {
                    kept[idxF] = false;
                    droppedCross++;
                } else {
                    keepSet.add(idxF);
                }
            }
            report(progress, "Cross-marker: dropped " + droppedCross + " correlated features");
        }

        // ── Stage 4: guardrail — keep ≥1 feature per marker group ───────────
        List<String> forceKept = new ArrayList<>();
        List<String> markersLost = new ArrayList<>();
        Map<String, List<Integer>> allGroupIndices = new LinkedHashMap<>();
        for (int j = 0; j < nFeatures; j++) {
            allGroupIndices.computeIfAbsent(groupOf[j], k -> new ArrayList<>()).add(j);
        }
        for (var entry : allGroupIndices.entrySet()) {
            String group = entry.getKey();
            boolean anyKept = entry.getValue().stream().anyMatch(j -> kept[j]);
            if (!anyKept) {
                if (opts.keepAtLeastOnePerGroup()) {
                    int best = entry.getValue().get(0);
                    double bestStd = std[best];
                    for (int j : entry.getValue()) {
                        if (std[j] > bestStd) {
                            bestStd = std[j];
                            best = j;
                        }
                    }
                    kept[best] = true;
                    forceKept.add(group);
                } else {
                    markersLost.add(group);
                }
            }
        }

        List<String> keptNames = new ArrayList<>();
        for (int j = 0; j < nFeatures; j++) {
            if (kept[j]) keptNames.add(names[j]);
        }

        Set<String> retainedSet = new LinkedHashSet<>();
        for (int j = 0; j < nFeatures; j++) {
            if (kept[j]) retainedSet.add(groupOf[j]);
        }
        List<String> markersRetained = new ArrayList<>(retainedSet);

        logger.info(
                "Pruned {} -> {} features ({} marker groups retained, {} force-kept, {} lost; {} cells)",
                nFeatures,
                keptNames.size(),
                markersRetained.size(),
                forceKept.size(),
                markersLost.size(),
                nCells);

        return new PruneResult(
                keptNames,
                nFeatures,
                droppedConstant,
                droppedWithin,
                droppedCross,
                forceKept,
                markersRetained,
                markersLost,
                nCells);
    }

    /**
     * Extract the marker-group key for a feature.
     * <p>Convention: text before the first {@code ": "} separator. Features
     * without that separator form their own singleton group keyed by the full
     * feature name.
     */
    public static String extractGroup(String featureName) {
        int colon = featureName.indexOf(": ");
        if (colon <= 0) return featureName;
        return featureName.substring(0, colon);
    }

    private static List<String> groupKeys(List<String> featureNames) {
        Set<String> groups = new LinkedHashSet<>();
        for (String name : featureNames) groups.add(extractGroup(name));
        return new ArrayList<>(groups);
    }

    /**
     * Pearson correlation between two pre-standardised vectors of equal length:
     * r = (1/N) * sum(a_i * b_i).
     */
    private static double pearson(double[] a, double[] b) {
        double sum = 0.0;
        int n = a.length;
        for (int i = 0; i < n; i++) sum += a[i] * b[i];
        return sum / n;
    }

    private static void report(Consumer<String> progress, String msg) {
        if (progress != null) {
            try {
                progress.accept(msg);
            } catch (Exception ignored) {
            }
        }
    }
}
