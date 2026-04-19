package qupath.ext.celltune.gating;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;

import java.util.*;
import java.util.function.Consumer;

/**
 * Java port of Python CellTune's {@code auto_landmark.py}.
 * <p>
 * Applies cascading threshold tiers to identify high-confidence "landmark" cells
 * for each cell type defined in a rule table. Supports three modes:
 * <ul>
 *   <li><b>INTENSITY</b> — thresholds on mean intensity percentiles</li>
 *   <li><b>PROBABILITY</b> — thresholds on marker positivity probabilities</li>
 *   <li><b>BOTH</b> — a cell must pass both intensity and probability thresholds</li>
 * </ul>
 * <p>
 * For each cell type, the engine picks the <em>strictest</em> threshold tier that
 * produces at least {@code minCells} landmarks. This makes common cell types get
 * strict gating while rare types get relaxed thresholds automatically.
 */
public class AutoLandmarker {

    private static final Logger logger = LoggerFactory.getLogger(AutoLandmarker.class);

    /** Gating mode — which measurement types to threshold. */
    public enum Mode { INTENSITY, PROBABILITY, BOTH }

    /** Default minimum landmark cells per cell type. */
    public static final int DEFAULT_MIN_CELLS = 10;

    // ── Threshold cascades (from Python CellTune) ───────────────────────────

    /** BOTH mode: (posProb, negProb, negStrictProb, posPercentile, negPercentile, negStrictPercentile) */
    private static final double[][] COMBINED_THRESHOLDS = {
        {0.99, 0.10, 0.06, 40, 25, 10},
        {0.98, 0.10, 0.06, 40, 25, 10},
        {0.95, 0.10, 0.06, 40, 25, 10},
        {0.95, 0.10, 0.06, 35, 25, 10},
        {0.95, 0.15, 0.06, 35, 25, 10},
        {0.90, 0.10, 0.06, 40, 25, 10},
        {0.90, 0.15, 0.10, 35, 30, 15},
        {0.90, 0.15, 0.10, 30, 30, 15},
        {0.85, 0.15, 0.10, 35, 30, 15},
        {0.85, 0.15, 0.10, 30, 30, 15},
        {0.85, 0.20, 0.10, 30, 35, 15},
        {0.85, 0.20, 0.15, 30, 35, 15},
        {0.85, 0.20, 0.20, 25, 40, 20},
        {0.80, 0.20, 0.20, 25, 40, 20},
        {0.80, 0.20, 0.20, 15, 50, 25},
        {0.75, 0.25, 0.25, 15, 50, 40},
        {0.70, 0.30, 0.25, 15, 50, 40},
    };

    /** PROBABILITY mode: (posThresh, negThresh, negStrictThresh) */
    private static final double[][] PROBABILITY_THRESHOLDS = {
        {0.98, 0.08, 0.04},
        {0.98, 0.10, 0.04},
        {0.95, 0.08, 0.04},
        {0.95, 0.10, 0.06},
        {0.95, 0.12, 0.06},
        {0.90, 0.10, 0.06},
        {0.90, 0.12, 0.08},
        {0.90, 0.15, 0.08},
        {0.85, 0.15, 0.10},
        {0.85, 0.17, 0.12},
        {0.85, 0.20, 0.15},
        {0.80, 0.20, 0.15},
        {0.80, 0.22, 0.18},
        {0.75, 0.25, 0.20},
        {0.70, 0.30, 0.25},
    };

    /** INTENSITY mode: (posPercentile, negPercentile, negStrictPercentile) */
    private static final double[][] INTENSITY_THRESHOLDS = {
        {95, 30, 15},
        {90, 30, 15},
        {90, 32, 17},
        {85, 30, 15},
        {85, 32, 17},
        {80, 32, 17},
        {80, 35, 20},
        {75, 35, 20},
        {75, 37, 22},
        {70, 37, 22},
        {70, 40, 25},
        {65, 40, 25},
        {65, 42, 27},
        {62, 42, 27},
        {62, 45, 30},
        {60, 45, 30},
    };

    // ── Result type ─────────────────────────────────────────────────────────

    /** Result of auto-landmarking for a single cell type. */
    public record LandmarkResult(
            String cellType,
            List<PathObject> cells,
            int thresholdTierUsed,
            String thresholdDescription
    ) {}

    // ── Main entry point ────────────────────────────────────────────────────

    /**
     * Run automated landmarking on a set of detections.
     *
     * @param detections      all cell detections in the image
     * @param rules           gating rules for each cell type (from rule table)
     * @param channelList     ordered list of marker/channel names used in rules
     * @param mode            gating mode
     * @param intensitySuffix suffix to find intensity measurements (e.g. ": Mean", "__Mean__Cell")
     * @param probabilitySuffix suffix to find probability measurements (e.g. "__Probability")
     * @param minCells        minimum landmark cells per cell type
     * @param log             optional progress logger
     * @return map of cell type → landmark result
     */
    public static Map<String, LandmarkResult> computeLandmarks(
            Collection<PathObject> detections,
            List<GatingRule> rules,
            List<String> channelList,
            Mode mode,
            String intensitySuffix,
            String probabilitySuffix,
            int minCells,
            Consumer<String> log) {

        if (log == null) log = s -> {};
        int nCells = detections.size();
        int nChannels = channelList.size();

        log.accept("Auto-landmarking: " + nCells + " cells, " + nChannels
                + " channels, " + rules.size() + " cell types, mode=" + mode);

        // Convert detections to indexed list
        List<PathObject> cellList = (detections instanceof List)
                ? (List<PathObject>) detections
                : new ArrayList<>(detections);

        // ── Extract measurement arrays ──────────────────────────────────────
        double[][] intensityArray = null;
        double[][] probabilityArray = null;

        if (mode == Mode.INTENSITY || mode == Mode.BOTH) {
            intensityArray = extractMeasurements(cellList, channelList, intensitySuffix);
            log.accept("Extracted intensity measurements");
        }
        if (mode == Mode.PROBABILITY || mode == Mode.BOTH) {
            probabilityArray = extractMeasurements(cellList, channelList, probabilitySuffix);
            log.accept("Extracted probability measurements");
        }

        // ── Select threshold cascade ────────────────────────────────────────
        double[][] thresholds = switch (mode) {
            case BOTH -> COMBINED_THRESHOLDS;
            case PROBABILITY -> PROBABILITY_THRESHOLDS;
            case INTENSITY -> INTENSITY_THRESHOLDS;
        };

        // ── Run all threshold tiers ─────────────────────────────────────────
        // For each tier × each cell type → list of passing cell indices
        int nTiers = thresholds.length;
        int nRules = rules.size();
        int[][] countsPerTier = new int[nRules][nTiers]; // [rule][tier] = count
        List<List<int[]>> allIndices = new ArrayList<>(); // [tier] → [rule] → int[] indices
        for (int t = 0; t < nTiers; t++) {
            List<int[]> tierIndices = new ArrayList<>();
            double[] thresh = thresholds[t];

            // Compute threshold masks for this tier
            boolean[][] abovePosProb = null, aboveNegProb = null, aboveNegStrictProb = null;
            boolean[][] abovePosInt = null, aboveNegInt = null, aboveNegStrictInt = null;

            if (mode == Mode.BOTH) {
                double posProb = thresh[0], negProb = thresh[1], negStrictProb = thresh[2];
                double posPctile = thresh[3], negPctile = thresh[4], negStrictPctile = thresh[5];

                abovePosProb = thresholdAbove(probabilityArray, posProb);
                aboveNegProb = thresholdAbove(probabilityArray, negProb);
                aboveNegStrictProb = thresholdAbove(probabilityArray, negStrictProb);

                double[] posPctThresh = computePercentileThresholds(intensityArray, channelList, posPctile);
                double[] negPctThresh = computePercentileThresholds(intensityArray, channelList, negPctile);
                double[] negStrictPctThresh = computePercentileThresholds(intensityArray, channelList, negStrictPctile);

                abovePosInt = thresholdAbovePerChannel(intensityArray, posPctThresh);
                aboveNegInt = thresholdAbovePerChannel(intensityArray, negPctThresh);
                aboveNegStrictInt = thresholdAbovePerChannel(intensityArray, negStrictPctThresh);

            } else if (mode == Mode.PROBABILITY) {
                double posProb = thresh[0], negProb = thresh[1], negStrictProb = thresh[2];
                abovePosProb = thresholdAbove(probabilityArray, posProb);
                aboveNegProb = thresholdAbove(probabilityArray, negProb);
                aboveNegStrictProb = thresholdAbove(probabilityArray, negStrictProb);

            } else { // INTENSITY
                double posPctile = thresh[0], negPctile = thresh[1], negStrictPctile = thresh[2];

                double[] posPctThresh = computePercentileThresholds(intensityArray, channelList, posPctile);
                double[] negPctThresh = computePercentileThresholds(intensityArray, channelList, negPctile);
                double[] negStrictPctThresh = computePercentileThresholds(intensityArray, channelList, negStrictPctile);

                abovePosInt = thresholdAbovePerChannel(intensityArray, posPctThresh);
                aboveNegInt = thresholdAbovePerChannel(intensityArray, negPctThresh);
                aboveNegStrictInt = thresholdAbovePerChannel(intensityArray, negStrictPctThresh);
            }

            // Evaluate each rule at this tier
            for (int r = 0; r < nRules; r++) {
                GatingRule rule = rules.get(r);
                int[] indices = applyRule(rule, channelList, nCells, mode,
                        abovePosProb, aboveNegProb, aboveNegStrictProb,
                        abovePosInt, aboveNegInt, aboveNegStrictInt);
                tierIndices.add(indices);
                countsPerTier[r][t] = indices.length;
            }
            allIndices.add(tierIndices);
        }

        // ── Select best tier per cell type ──────────────────────────────────
        Map<String, LandmarkResult> results = new LinkedHashMap<>();
        for (int r = 0; r < nRules; r++) {
            GatingRule rule = rules.get(r);
            String ct = rule.getCellType();
            int bestTier = -1;
            for (int t = 0; t < nTiers; t++) {
                if (countsPerTier[r][t] >= minCells) {
                    bestTier = t;
                    break;
                }
            }

            List<PathObject> landmarkCells;
            String tierDesc;
            if (bestTier >= 0) {
                int[] cellIndices = allIndices.get(bestTier).get(r);
                landmarkCells = new ArrayList<>(cellIndices.length);
                for (int idx : cellIndices) {
                    landmarkCells.add(cellList.get(idx));
                }
                tierDesc = "tier " + (bestTier + 1) + "/" + nTiers
                        + " (" + cellIndices.length + " cells)";
                log.accept("  " + ct + ": " + cellIndices.length
                        + " landmarks at " + tierDesc);
            } else {
                // No tier met minimum — use the most relaxed tier's results
                int lastTier = nTiers - 1;
                int[] cellIndices = allIndices.get(lastTier).get(r);
                landmarkCells = new ArrayList<>(cellIndices.length);
                for (int idx : cellIndices) {
                    landmarkCells.add(cellList.get(idx));
                }
                tierDesc = "fallback (tier " + nTiers + "/" + nTiers
                        + ", " + cellIndices.length + " cells < min " + minCells + ")";
                log.accept("  " + ct + ": WARNING — only " + cellIndices.length
                        + " landmarks (below min " + minCells + ")");
            }

            results.put(ct, new LandmarkResult(ct, landmarkCells,
                    bestTier >= 0 ? bestTier : nTiers - 1, tierDesc));
        }

        int totalLandmarks = results.values().stream().mapToInt(r -> r.cells().size()).sum();
        log.accept("Auto-landmarking complete: " + totalLandmarks
                + " total landmarks across " + results.size() + " cell types");

        return results;
    }

    // ── Measurement extraction ──────────────────────────────────────────────

    /**
     * Extract measurements for each cell × each channel.
     *
     * @param cells       detection objects
     * @param channelList channel names
     * @param suffix      measurement suffix (e.g. ": Mean")
     * @return [nCells][nChannels] array; NaN replaced with 0
     */
    private static double[][] extractMeasurements(List<PathObject> cells,
                                                   List<String> channelList,
                                                   String suffix) {
        int nCells = cells.size();
        int nCh = channelList.size();
        double[][] data = new double[nCells][nCh];

        for (int i = 0; i < nCells; i++) {
            var mlist = cells.get(i).getMeasurementList();
            for (int j = 0; j < nCh; j++) {
                String key = channelList.get(j) + suffix;
                double v = mlist.get(key);
                data[i][j] = Double.isNaN(v) ? 0.0 : v;
            }
        }
        return data;
    }

    // ── Thresholding helpers ────────────────────────────────────────────────

    /** Create boolean mask: data[i][j] > threshold */
    private static boolean[][] thresholdAbove(double[][] data, double threshold) {
        int nRows = data.length;
        int nCols = data[0].length;
        boolean[][] result = new boolean[nRows][nCols];
        for (int i = 0; i < nRows; i++) {
            for (int j = 0; j < nCols; j++) {
                result[i][j] = data[i][j] > threshold;
            }
        }
        return result;
    }

    /** Create boolean mask: data[i][j] > perChannelThreshold[j] */
    private static boolean[][] thresholdAbovePerChannel(double[][] data, double[] thresholds) {
        int nRows = data.length;
        int nCols = data[0].length;
        boolean[][] result = new boolean[nRows][nCols];
        for (int i = 0; i < nRows; i++) {
            for (int j = 0; j < nCols; j++) {
                result[i][j] = data[i][j] > thresholds[j];
            }
        }
        return result;
    }

    /**
     * Compute per-channel percentile thresholds (of positive values only).
     * For subregion/mask markers, use max-epsilon instead of percentile.
     */
    private static double[] computePercentileThresholds(double[][] data,
                                                         List<String> channelList,
                                                         double percentile) {
        int nCh = channelList.size();
        double[] thresholds = new double[nCh];

        for (int j = 0; j < nCh; j++) {
            String ch = channelList.get(j);

            // Collect positive values for this channel
            List<Double> positives = new ArrayList<>();
            for (double[] row : data) {
                if (row[j] > 0) positives.add(row[j]);
            }

            if (positives.isEmpty()) {
                thresholds[j] = 0;
                continue;
            }

            // Subregion/mask channels use binary threshold
            if (ch.contains("__Subregion") || ch.contains("__Mask")) {
                double max = positives.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                thresholds[j] = max - 0.00001;
            } else {
                thresholds[j] = percentile(positives, percentile);
            }
        }
        return thresholds;
    }

    /** Compute percentile of a list of values. */
    private static double percentile(List<Double> values, double pct) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (sorted.length == 0) return 0;
        double idx = (pct / 100.0) * (sorted.length - 1);
        int lo = (int) Math.floor(idx);
        int hi = Math.min(lo + 1, sorted.length - 1);
        double frac = idx - lo;
        return sorted[lo] + frac * (sorted[hi] - sorted[lo]);
    }

    // ── Rule application ────────────────────────────────────────────────────

    /**
     * Apply a single gating rule to all cells and return indices of passing cells.
     */
    private static int[] applyRule(GatingRule rule, List<String> channelList, int nCells,
                                    Mode mode,
                                    boolean[][] abovePosProb, boolean[][] aboveNegProb,
                                    boolean[][] aboveNegStrictProb,
                                    boolean[][] abovePosInt, boolean[][] aboveNegInt,
                                    boolean[][] aboveNegStrictInt) {

        // Build channel index map
        Map<String, Integer> chIdx = new HashMap<>();
        for (int j = 0; j < channelList.size(); j++) {
            chIdx.put(channelList.get(j), j);
        }

        // Classify channels by encoding
        List<Integer> andCols = new ArrayList<>();
        List<Integer> orCols = new ArrayList<>();
        List<Integer> softNotCols = new ArrayList<>();
        List<Integer> strictNotCols = new ArrayList<>();

        for (String ch : channelList) {
            int enc = rule.getEncoding(ch);
            Integer col = chIdx.get(ch);
            if (col == null) continue;
            switch (enc) {
                case 1 -> andCols.add(col);
                case 2 -> orCols.add(col);
                case -1 -> softNotCols.add(col);
                case -3 -> strictNotCols.add(col);
                // 0 (secondary) is not used for gating
            }
        }

        // Start with all cells
        boolean[] keep = new boolean[nCells];
        Arrays.fill(keep, true);

        // Apply AND rule: all must-have markers above positive threshold
        if (!andCols.isEmpty()) {
            for (int i = 0; i < nCells; i++) {
                if (!keep[i]) continue;
                boolean pass = true;
                for (int col : andCols) {
                    boolean probOk = (abovePosProb == null) || abovePosProb[i][col];
                    boolean intOk = (abovePosInt == null) || abovePosInt[i][col];
                    if (mode == Mode.BOTH) {
                        if (!probOk || !intOk) { pass = false; break; }
                    } else if (mode == Mode.PROBABILITY) {
                        if (!probOk) { pass = false; break; }
                    } else {
                        if (!intOk) { pass = false; break; }
                    }
                }
                if (!pass) keep[i] = false;
            }
        }

        // Apply OR rule: at least one or-expression marker above positive threshold
        if (!orCols.isEmpty()) {
            for (int i = 0; i < nCells; i++) {
                if (!keep[i]) continue;
                boolean anyPass = false;
                for (int col : orCols) {
                    boolean probOk = (abovePosProb == null) || abovePosProb[i][col];
                    boolean intOk = (abovePosInt == null) || abovePosInt[i][col];
                    if (mode == Mode.BOTH) {
                        if (probOk && intOk) { anyPass = true; break; }
                    } else if (mode == Mode.PROBABILITY) {
                        if (probOk) { anyPass = true; break; }
                    } else {
                        if (intOk) { anyPass = true; break; }
                    }
                }
                if (!anyPass) keep[i] = false;
            }
        }

        // Apply soft NOT: none of the soft-NOT markers above negative threshold
        if (!softNotCols.isEmpty()) {
            for (int i = 0; i < nCells; i++) {
                if (!keep[i]) continue;
                for (int col : softNotCols) {
                    boolean probHigh = (aboveNegProb != null) && aboveNegProb[i][col];
                    boolean intHigh = (aboveNegInt != null) && aboveNegInt[i][col];
                    if (mode == Mode.BOTH) {
                        if (probHigh || intHigh) { keep[i] = false; break; }
                    } else if (mode == Mode.PROBABILITY) {
                        if (probHigh) { keep[i] = false; break; }
                    } else {
                        if (intHigh) { keep[i] = false; break; }
                    }
                }
            }
        }

        // Apply strict NOT: none of the strict-NOT markers above strict-negative threshold
        if (!strictNotCols.isEmpty()) {
            for (int i = 0; i < nCells; i++) {
                if (!keep[i]) continue;
                for (int col : strictNotCols) {
                    boolean probHigh = (aboveNegStrictProb != null) && aboveNegStrictProb[i][col];
                    boolean intHigh = (aboveNegStrictInt != null) && aboveNegStrictInt[i][col];
                    if (mode == Mode.BOTH) {
                        if (probHigh || intHigh) { keep[i] = false; break; }
                    } else if (mode == Mode.PROBABILITY) {
                        if (probHigh) { keep[i] = false; break; }
                    } else {
                        if (intHigh) { keep[i] = false; break; }
                    }
                }
            }
        }

        // Collect passing indices
        int count = 0;
        for (boolean b : keep) if (b) count++;
        int[] result = new int[count];
        int idx = 0;
        for (int i = 0; i < nCells; i++) {
            if (keep[i]) result[idx++] = i;
        }
        return result;
    }
}
