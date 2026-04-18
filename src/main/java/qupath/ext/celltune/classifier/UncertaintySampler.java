package qupath.ext.celltune.classifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.model.CellPrediction;
import qupath.ext.celltune.model.PopulationSet;

import java.util.*;

/**
 * Weighted sampling of disagreement cells for human review.
 * <p>
 * Mirrors CellTune's sampling strategy: cells where the two models disagree
 * are weighted so that cell types with low inter-model agreement are sampled
 * more heavily. This ensures the human's labelling time is focused on the
 * model's worst failure modes first.
 * <p>
 * Weight for a disagreement cell predicted as class {@code i} (model 1)
 * and class {@code j} (model 2) is:
 * <pre>
 *     w = (2 - agreementRate[i] - agreementRate[j])
 * </pre>
 * Higher weight → more likely to be sampled.
 */
public class UncertaintySampler {

    private static final Logger logger = LoggerFactory.getLogger(UncertaintySampler.class);

    private UncertaintySampler() {} // utility class

    /**
     * Sample N disagreement cells weighted by confusion severity.
     *
     * @param predALL        the Pred_ALL population set (all predictions)
     * @param classNames     ordered class name list
     * @param agreementRates per-class agreement rates from ConfusionMatrixView
     *                       (index matches classNames)
     * @param sampleSize     number of cells to sample
     * @param rng            random number generator (for reproducibility)
     * @return list of sampled cell IDs, ordered by weight (heaviest first),
     *         with at most {@code sampleSize} entries
     */
    public static List<String> sample(PopulationSet predALL,
                                      List<String> classNames,
                                      double[] agreementRates,
                                      int sampleSize,
                                      Random rng) {

        // ── 1. Collect disagreement cells with weights ──────────────────────
        List<WeightedCell> candidates = new ArrayList<>();
        double totalWeight = 0;

        for (var entry : predALL.getAll().entrySet()) {
            CellPrediction pred = entry.getValue();
            if (!pred.isDisagreement()) continue;

            int idxI = classNames.indexOf(pred.getModel1Label());
            int idxJ = classNames.indexOf(pred.getModel2Label());

            // If a class is unknown (shouldn't happen), use 0.5 as default rate
            double rateI = (idxI >= 0 && idxI < agreementRates.length) ? agreementRates[idxI] : 0.5;
            double rateJ = (idxJ >= 0 && idxJ < agreementRates.length) ? agreementRates[idxJ] : 0.5;

            double weight = 2.0 - rateI - rateJ;
            // Ensure a minimum weight so every disagreement has some chance
            weight = Math.max(weight, 0.01);

            candidates.add(new WeightedCell(entry.getKey(), weight));
            totalWeight += weight;
        }

        if (candidates.isEmpty()) {
            logger.info("No disagreement cells to sample.");
            return List.of();
        }

        logger.info("Sampling from {} disagreement cells (total weight={:.2f})",
                candidates.size(), totalWeight);

        // If we want more than available, return all (shuffled)
        if (sampleSize >= candidates.size()) {
            Collections.shuffle(candidates, rng);
            return candidates.stream()
                    .map(WeightedCell::cellId)
                    .toList();
        }

        // ── 2. Weighted sampling without replacement ────────────────────────
        List<String> sampled = new ArrayList<>(sampleSize);
        Set<Integer> chosen = new HashSet<>();

        for (int s = 0; s < sampleSize; s++) {
            double dart = rng.nextDouble() * totalWeight;
            double cumulative = 0;
            int pick = -1;

            for (int i = 0; i < candidates.size(); i++) {
                if (chosen.contains(i)) continue;
                cumulative += candidates.get(i).weight();
                if (cumulative >= dart) {
                    pick = i;
                    break;
                }
            }

            // Fallback: pick the last unchosen candidate
            if (pick < 0) {
                for (int i = candidates.size() - 1; i >= 0; i--) {
                    if (!chosen.contains(i)) {
                        pick = i;
                        break;
                    }
                }
            }

            if (pick >= 0) {
                chosen.add(pick);
                sampled.add(candidates.get(pick).cellId());
                totalWeight -= candidates.get(pick).weight();
            }
        }

        logger.info("Sampled {} cells for review", sampled.size());
        return sampled;
    }

    /**
     * Convenience overload using a default Random seed.
     */
    public static List<String> sample(PopulationSet predALL,
                                      List<String> classNames,
                                      double[] agreementRates,
                                      int sampleSize) {
        return sample(predALL, classNames, agreementRates, sampleSize, new Random());
    }

    private record WeightedCell(String cellId, double weight) {}
}
