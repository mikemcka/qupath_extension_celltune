package qupath.ext.celltune.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Contextualises whole-image pixel statistics against the whole project.
 * <p>
 * Given one {@link ImagePixelStats.ImageStats} per project image, this computes
 * — for every channel and for a handful of image-level summary scalars — the
 * cohort median, a robust (MAD-scaled) z-score, and a percentile rank, then
 * applies deterministic threshold rules to assign each image a verdict, a set of
 * flag reason codes, and a plain-English review explaining what the image's
 * numbers mean relative to its peers.
 * <p>
 * The robust-z definition is identical to {@link CohortAnomalyAnalyzer}:
 * {@code z = 0.6745 · (x − median) / MAD}. Like that class, this one is free of
 * any QuPath/JavaFX dependency and is unit-tested in isolation.
 */
public final class PixelCohortAnalyzer {

    private PixelCohortAnalyzer() {
    }

    /** Analyse with {@link PixelCohortReport#DEFAULT_THRESHOLDS}. */
    public static PixelCohortReport analyze(List<ImagePixelStats.ImageStats> inputs) {
        return analyze(inputs, PixelCohortReport.DEFAULT_THRESHOLDS);
    }

    /**
     * Analyse a cohort of per-image pixel statistics.
     *
     * @param inputs     per-image statistics (nulls are skipped)
     * @param thresholds verdict cutoffs; {@code null} uses the defaults
     * @return the cohort report, images sorted worst-first
     */
    public static PixelCohortReport analyze(
            List<ImagePixelStats.ImageStats> inputs,
            PixelCohortReport.Thresholds thresholds) {

        if (thresholds == null) {
            thresholds = PixelCohortReport.DEFAULT_THRESHOLDS;
        }

        var safe = new ArrayList<ImagePixelStats.ImageStats>();
        if (inputs != null) {
            for (var in : inputs) {
                if (in != null) {
                    safe.add(in);
                }
            }
        }
        if (safe.isEmpty()) {
            return new PixelCohortReport(List.of());
        }

        int nImages = safe.size();

        // Channel name union, in first-seen order.
        var channelNames = new LinkedHashSet<String>();
        for (var img : safe) {
            for (var cs : img.channels()) {
                channelNames.add(cs.channel());
            }
        }
        var orderedChannels = new ArrayList<>(channelNames);

        // Per-image channel lookup.
        List<Map<String, ImagePixelStats.ChannelStats>> byChannel = new ArrayList<>(nImages);
        for (var img : safe) {
            var map = new LinkedHashMap<String, ImagePixelStats.ChannelStats>();
            for (var cs : img.channels()) {
                map.putIfAbsent(cs.channel(), cs);
            }
            byChannel.add(map);
        }

        // ── Image-level summary scalars ─────────────────────────────────────────
        double[] emptyFraction = new double[nImages];
        double[] meanForeground = new double[nImages];
        double[] maxSaturation = new double[nImages];
        String[] maxSaturationChannel = new String[nImages];
        double[] medianDynamicRange = new double[nImages];

        for (int i = 0; i < nImages; i++) {
            var img = safe.get(i);
            emptyFraction[i] = img.emptyFraction();

            var fgList = new ArrayList<Double>();
            var dynList = new ArrayList<Double>();
            double bestSat = Double.NaN;
            String bestSatCh = null;
            for (var cs : img.channels()) {
                if (!Double.isNaN(cs.foregroundCoverage())) {
                    fgList.add(cs.foregroundCoverage());
                }
                if (!Double.isNaN(cs.dynamicRange())) {
                    dynList.add(cs.dynamicRange());
                }
                double sat = cs.saturationFraction();
                if (!Double.isNaN(sat) && (Double.isNaN(bestSat) || sat > bestSat)) {
                    bestSat = sat;
                    bestSatCh = cs.channel();
                }
            }
            meanForeground[i] = mean(fgList);
            medianDynamicRange[i] = medianOf(dynList);
            maxSaturation[i] = bestSat;
            maxSaturationChannel[i] = bestSatCh;
        }

        double[] emptyFractionZ = robustZ(emptyFraction);
        double[] meanForegroundZ = robustZ(meanForeground);
        double[] maxSaturationZ = robustZ(maxSaturation);
        double[] medianDynamicRangeZ = robustZ(medianDynamicRange);

        double medEmptyFraction = medianIgnoreNaN(emptyFraction);
        double medMeanForeground = medianIgnoreNaN(meanForeground);
        double medMaxSaturation = medianIgnoreNaN(maxSaturation);
        double medMedianDynamicRange = medianIgnoreNaN(medianDynamicRange);

        // ── Per-channel metric z-scores and ranks ───────────────────────────────
        var medianZ = new LinkedHashMap<String, double[]>();
        var medianRank = new LinkedHashMap<String, double[]>();
        var p99Z = new LinkedHashMap<String, double[]>();
        var bgZ = new LinkedHashMap<String, double[]>();
        var fgZ = new LinkedHashMap<String, double[]>();
        var fgRank = new LinkedHashMap<String, double[]>();
        var dynZ = new LinkedHashMap<String, double[]>();
        var satZ = new LinkedHashMap<String, double[]>();

        for (String ch : orderedChannels) {
            double[] medianVals = new double[nImages];
            double[] p99Vals = new double[nImages];
            double[] bgVals = new double[nImages];
            double[] fgVals = new double[nImages];
            double[] dynVals = new double[nImages];
            double[] satVals = new double[nImages];
            for (int i = 0; i < nImages; i++) {
                var cs = byChannel.get(i).get(ch);
                medianVals[i] = cs == null ? Double.NaN : cs.median();
                p99Vals[i] = cs == null ? Double.NaN : cs.p99();
                bgVals[i] = cs == null ? Double.NaN : cs.backgroundFraction();
                fgVals[i] = cs == null ? Double.NaN : cs.foregroundCoverage();
                dynVals[i] = cs == null ? Double.NaN : cs.dynamicRange();
                satVals[i] = cs == null ? Double.NaN : cs.saturationFraction();
            }
            medianZ.put(ch, robustZ(medianVals));
            medianRank.put(ch, ranks(medianVals));
            p99Z.put(ch, robustZ(p99Vals));
            bgZ.put(ch, robustZ(bgVals));
            fgZ.put(ch, robustZ(fgVals));
            fgRank.put(ch, ranks(fgVals));
            dynZ.put(ch, robustZ(dynVals));
            satZ.put(ch, robustZ(satVals));
        }

        // ── Build per-image reports ─────────────────────────────────────────────
        var reports = new ArrayList<PixelCohortReport.ImageReport>(nImages);
        for (int i = 0; i < nImages; i++) {
            var img = safe.get(i);

            var channelContexts = new ArrayList<PixelCohortReport.ChannelContext>(img.channels().size());
            double maxAbsMedianZ = 0.0;
            String maxAbsMedianChannel = null;
            for (var cs : img.channels()) {
                String ch = cs.channel();
                double mz = at(medianZ, ch, i);
                channelContexts.add(new PixelCohortReport.ChannelContext(
                        ch, cs,
                        mz, at(medianRank, ch, i),
                        at(p99Z, ch, i),
                        at(bgZ, ch, i),
                        at(fgZ, ch, i), at(fgRank, ch, i),
                        at(dynZ, ch, i),
                        at(satZ, ch, i)));
                if (!Double.isNaN(mz) && Math.abs(mz) > maxAbsMedianZ) {
                    maxAbsMedianZ = Math.abs(mz);
                    maxAbsMedianChannel = ch;
                }
            }

            // ── Flags ──
            var flags = new ArrayList<String>(4);
            boolean backgroundHeavy = ge(emptyFractionZ[i], thresholds.backgroundEmptyZ())
                    || le(meanForegroundZ[i], -thresholds.backgroundForegroundZ());
            if (backgroundHeavy) {
                flags.add(PixelCohortReport.BACKGROUND_HEAVY);
            }
            boolean saturated = !Double.isNaN(maxSaturation[i])
                    && ((maxSaturation[i] >= thresholds.saturationMinFraction()
                            && ge(maxSaturationZ[i], thresholds.saturationZ()))
                        || maxSaturation[i] >= thresholds.saturationHardFraction());
            if (saturated) {
                flags.add(PixelCohortReport.SATURATED);
            }
            boolean weakSignal = le(medianDynamicRangeZ[i], -thresholds.weakSignalZ());
            if (weakSignal) {
                flags.add(PixelCohortReport.WEAK_SIGNAL);
            }
            boolean intensityOutlier = maxAbsMedianZ >= thresholds.intensityOutlierZ();
            if (intensityOutlier) {
                flags.add(PixelCohortReport.INTENSITY_OUTLIER);
            }

            String verdict = verdict(flags);

            double score = relu(emptyFractionZ[i])
                    + relu(-meanForegroundZ[i])
                    + (saturated ? relu(maxSaturationZ[i]) : 0.0)
                    + relu(-medianDynamicRangeZ[i])
                    + relu(maxAbsMedianZ - 2.0);

            String narrative = buildNarrative(
                    img, verdict, flags,
                    emptyFraction[i], emptyFractionZ[i], medEmptyFraction,
                    meanForeground[i], meanForegroundZ[i], medMeanForeground,
                    maxSaturation[i], maxSaturationChannel[i], maxSaturationZ[i], medMaxSaturation,
                    medianDynamicRange[i], medianDynamicRangeZ[i], medMedianDynamicRange,
                    maxAbsMedianChannel, maxAbsMedianZ, channelContexts, thresholds);

            reports.add(new PixelCohortReport.ImageReport(
                    img.imageName(), verdict, flags, score, narrative,
                    emptyFraction[i], emptyFractionZ[i],
                    meanForeground[i], meanForegroundZ[i],
                    maxSaturation[i], maxSaturationChannel[i], maxSaturationZ[i],
                    medianDynamicRange[i], medianDynamicRangeZ[i],
                    channelContexts));
        }

        reports.sort(Comparator
                .comparingDouble(PixelCohortReport.ImageReport::score).reversed()
                .thenComparing(PixelCohortReport.ImageReport::imageName));

        return new PixelCohortReport(reports);
    }

    // ── Narrative ───────────────────────────────────────────────────────────────

    private static String buildNarrative(
            ImagePixelStats.ImageStats img,
            String verdict,
            List<String> flags,
            double emptyFraction, double emptyFractionZ, double medEmptyFraction,
            double meanForeground, double meanForegroundZ, double medMeanForeground,
            double maxSaturation, String maxSaturationChannel, double maxSaturationZ, double medMaxSaturation,
            double medianDynamicRange, double medianDynamicRangeZ, double medMedianDynamicRange,
            String maxAbsMedianChannel, double maxAbsMedianZ,
            List<PixelCohortReport.ChannelContext> channels,
            PixelCohortReport.Thresholds t) {

        var sb = new StringBuilder();
        sb.append(img.imageName()).append(" — ").append(verdict).append('\n');

        // Foreground / background context.
        if (flags.contains(PixelCohortReport.BACKGROUND_HEAVY)
                || notable(emptyFractionZ) || notable(meanForegroundZ)) {
            sb.append("• Foreground coverage ").append(fmtPct(meanForeground))
                    .append(" (cohort median ").append(fmtPct(medMeanForeground))
                    .append(", ").append(fmtZ(meanForegroundZ)).append(" MAD).");
            if (!Double.isNaN(emptyFraction)) {
                sb.append(" Empty/glass ").append(fmtPct(emptyFraction))
                        .append(" (median ").append(fmtPct(medEmptyFraction))
                        .append(", ").append(fmtZ(emptyFractionZ)).append(" MAD).");
            }
            sb.append('\n');
        }

        // Saturation context.
        if (flags.contains(PixelCohortReport.SATURATED) || notable(maxSaturationZ)) {
            sb.append("• Peak saturation ").append(fmtPct(maxSaturation));
            if (maxSaturationChannel != null) {
                sb.append(" in ").append(maxSaturationChannel);
            }
            sb.append(" (cohort median ").append(fmtPct(medMaxSaturation))
                    .append(", ").append(fmtZ(maxSaturationZ)).append(" MAD).\n");
        }

        // Dynamic range / weak signal context.
        if (flags.contains(PixelCohortReport.WEAK_SIGNAL) || notable(medianDynamicRangeZ)) {
            sb.append("• Median dynamic range ").append(fmtNum(medianDynamicRange))
                    .append(" (cohort median ").append(fmtNum(medMedianDynamicRange))
                    .append(", ").append(fmtZ(medianDynamicRangeZ)).append(" MAD).\n");
        }

        // Intensity-outlier context: name the channel and where it ranks.
        if (flags.contains(PixelCohortReport.INTENSITY_OUTLIER) && maxAbsMedianChannel != null) {
            PixelCohortReport.ChannelContext cc = null;
            for (var c : channels) {
                if (c.channel().equals(maxAbsMedianChannel)) {
                    cc = c;
                    break;
                }
            }
            if (cc != null) {
                sb.append("• ").append(maxAbsMedianChannel).append(" median ")
                        .append(fmtNum(cc.stats().median()))
                        .append(" is ").append(rankPhrase(cc.medianRankPercent()))
                        .append(" of the project (").append(fmtZ(cc.medianZ())).append(" MAD).\n");
            }
        }

        if (flags.isEmpty()) {
            sb.append("• Pixel statistics are within the normal range for this project.\n");
        }

        sb.append("Suggested action: ").append(suggestedAction(verdict, flags));
        return sb.toString();
    }

    private static String suggestedAction(String verdict, List<String> flags) {
        if (flags.isEmpty()) {
            return "include — looks normal.";
        }
        if (flags.contains(PixelCohortReport.BACKGROUND_HEAVY)) {
            return "review / consider removing — mostly background.";
        }
        if (flags.contains(PixelCohortReport.SATURATED)) {
            return "review acquisition — channel(s) clipped/over-exposed.";
        }
        if (flags.contains(PixelCohortReport.WEAK_SIGNAL)) {
            return "review staining/exposure — unusually flat signal.";
        }
        return "review — intensity profile differs from the cohort.";
    }

    private static String verdict(List<String> flags) {
        if (flags.isEmpty()) {
            return PixelCohortReport.VERDICT_OK;
        }
        if (flags.contains(PixelCohortReport.BACKGROUND_HEAVY)) {
            return "Background-heavy";
        }
        if (flags.contains(PixelCohortReport.SATURATED)) {
            return "Saturated";
        }
        if (flags.contains(PixelCohortReport.WEAK_SIGNAL)) {
            return "Weak signal";
        }
        return "Intensity outlier";
    }

    // ── Robust statistics helpers ────────────────────────────────────────────────

    private static double at(Map<String, double[]> map, String channel, int i) {
        double[] arr = map.get(channel);
        return arr == null ? Double.NaN : arr[i];
    }

    /**
     * Robust z-scores, NaN-preserving, MAD-scaled (0.6745 normaliser).
     * <p>
     * When the MAD is zero — common for metrics whose cohort baseline is a hard
     * floor, e.g. saturation fraction where most images are 0% — the score falls
     * back to a classic mean/std z so a genuine outlier above a flat baseline is
     * still detected rather than collapsing to zero.
     */
    static double[] robustZ(double[] values) {
        double[] out = new double[values.length];
        double median = medianIgnoreNaN(values);
        if (Double.isNaN(median)) {
            Arrays.fill(out, Double.NaN);
            return out;
        }
        double[] absDev = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            absDev[i] = Double.isNaN(values[i]) ? Double.NaN : Math.abs(values[i] - median);
        }
        double mad = medianIgnoreNaN(absDev);

        if (!Double.isNaN(mad) && mad >= 1e-12) {
            for (int i = 0; i < values.length; i++) {
                out[i] = Double.isNaN(values[i])
                        ? Double.NaN
                        : 0.6745 * (values[i] - median) / mad;
            }
            return out;
        }

        // Degenerate MAD — fall back to mean/std so outliers above a flat
        // baseline are still surfaced.
        double sum = 0.0;
        int n = 0;
        for (double v : values) {
            if (!Double.isNaN(v)) {
                sum += v;
                n++;
            }
        }
        double mean = n > 0 ? sum / n : Double.NaN;
        double sq = 0.0;
        for (double v : values) {
            if (!Double.isNaN(v)) {
                double d = v - mean;
                sq += d * d;
            }
        }
        double std = n > 0 ? Math.sqrt(sq / n) : Double.NaN;
        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(values[i])) {
                out[i] = Double.NaN;
            } else if (Double.isNaN(std) || std < 1e-12) {
                out[i] = 0.0;
            } else {
                out[i] = (values[i] - mean) / std;
            }
        }
        return out;
    }

    /** Percentile rank (0–100) of each value among the finite values; NaN-preserving. */
    static double[] ranks(double[] values) {
        double[] out = new double[values.length];
        int finite = 0;
        for (double v : values) {
            if (!Double.isNaN(v)) {
                finite++;
            }
        }
        if (finite == 0) {
            Arrays.fill(out, Double.NaN);
            return out;
        }
        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(values[i])) {
                out[i] = Double.NaN;
                continue;
            }
            int less = 0;
            int equal = 0;
            for (double v : values) {
                if (Double.isNaN(v)) {
                    continue;
                }
                if (v < values[i]) {
                    less++;
                } else if (v == values[i]) {
                    equal++;
                }
            }
            out[i] = ((less + 0.5 * equal) / finite) * 100.0;
        }
        return out;
    }

    private static double medianIgnoreNaN(double[] values) {
        int n = 0;
        for (double v : values) {
            if (!Double.isNaN(v)) {
                n++;
            }
        }
        if (n == 0) {
            return Double.NaN;
        }
        double[] finite = new double[n];
        int k = 0;
        for (double v : values) {
            if (!Double.isNaN(v)) {
                finite[k++] = v;
            }
        }
        Arrays.sort(finite);
        int mid = n / 2;
        return (n & 1) == 1 ? finite[mid] : 0.5 * (finite[mid - 1] + finite[mid]);
    }

    private static double medianOf(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return Double.NaN;
        }
        double[] arr = new double[values.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = values.get(i);
        }
        return medianIgnoreNaN(arr);
    }

    private static double mean(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    private static double relu(double x) {
        return (Double.isNaN(x) || x < 0.0) ? 0.0 : x;
    }

    private static boolean ge(double x, double threshold) {
        return !Double.isNaN(x) && x >= threshold;
    }

    private static boolean le(double x, double threshold) {
        return !Double.isNaN(x) && x <= threshold;
    }

    private static boolean notable(double z) {
        return !Double.isNaN(z) && Math.abs(z) >= 2.0;
    }

    // ── Formatting ────────────────────────────────────────────────────────────────

    private static String fmtPct(double frac) {
        if (Double.isNaN(frac)) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.1f%%", frac * 100.0);
    }

    private static String fmtZ(double z) {
        if (Double.isNaN(z)) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%+.1f", z);
    }

    private static String fmtNum(double v) {
        if (Double.isNaN(v)) {
            return "n/a";
        }
        if (Math.abs(v) >= 1000.0) {
            return String.format(Locale.ROOT, "%.0f", v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String rankPhrase(double rankPercent) {
        if (Double.isNaN(rankPercent)) {
            return "ranked n/a";
        }
        if (rankPercent <= 50.0) {
            return String.format(Locale.ROOT, "in the bottom %.0f%%", Math.max(1.0, rankPercent));
        }
        return String.format(Locale.ROOT, "in the top %.0f%%", Math.max(1.0, 100.0 - rankPercent));
    }
}
