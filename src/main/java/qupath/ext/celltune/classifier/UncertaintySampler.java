package qupath.ext.celltune.classifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.model.CellPrediction;
import qupath.ext.celltune.model.PopulationSet;

import java.util.*;

/**
 * Weighted sampling of cells for human review, with random exploration.
 * <p>
 * The sample is composed of two parts:
 * <ol>
 *   <li><b>Disagreement cells (90%)</b> — cells where XGBoost and LightGBM
 *       predict different classes, weighted so that class pairs with lower
 *       inter-model agreement are sampled more heavily. Weight formula:
 *       <pre>w = (2 - agreementRate[i] - agreementRate[j])</pre></li>
 *   <li><b>Exploration cells (10%)</b> — cells where both models agree,
 *       sampled uniformly at random. These guard against rare cell types
 *       that both models misclassify with high confidence — such cells
 *       never appear as disagreements and would otherwise be invisible
 *       to the active learning loop.</li>
 * </ol>
 * Exploration cells are appended at the end of the sample so that the
 * highest-priority disagreements are reviewed first.
 */
public class UncertaintySampler {

    private static final Logger logger = LoggerFactory.getLogger(UncertaintySampler.class);

    /** Fraction of the sample budget reserved for random exploration of agreement cells. */
    private static final double EXPLORATION_FRACTION = 0.10;

    private UncertaintySampler() {} // utility class

    /**
     * Sample cells for review: weighted disagreement cells plus a random
     * exploration component of agreement cells.
     *
     * @param predALL        the Pred_ALL population set (all predictions)
     * @param classNames     ordered class name list
     * @param agreementRates per-class agreement rates from ConfusionMatrixView
     *                       (index matches classNames)
     * @param sampleSize     total number of cells to sample (disagreement + exploration)
     * @param rng            random number generator (for reproducibility)
     * @return list of sampled cell IDs — disagreement cells first (weighted),
     *         exploration cells at the end (shuffled)
     */
    public static List<String> sample(PopulationSet predALL,
                                      List<String> classNames,
                                      double[] agreementRates,
                                      int sampleSize,
                                      Random rng) {

        // ── 1. Partition cells into disagreement and agreement pools ────────
        List<WeightedCell> disagreements = new ArrayList<>();
        List<String> agreements = new ArrayList<>();
        double totalWeight = 0;

        for (var entry : predALL.getAll().entrySet()) {
            CellPrediction pred = entry.getValue();
            if (pred.isDisagreement()) {
                int idxI = classNames.indexOf(pred.getModel1Label());
                int idxJ = classNames.indexOf(pred.getModel2Label());

                double rateI = (idxI >= 0 && idxI < agreementRates.length) ? agreementRates[idxI] : 0.5;
                double rateJ = (idxJ >= 0 && idxJ < agreementRates.length) ? agreementRates[idxJ] : 0.5;

                double weight = Math.max(2.0 - rateI - rateJ, 0.01);
                disagreements.add(new WeightedCell(entry.getKey(), weight));
                totalWeight += weight;
            } else {
                agreements.add(entry.getKey());
            }
        }

        // ── 2. Budget allocation ────────────────────────────────────────────
        int explorationBudget = agreements.isEmpty() ? 0
                : Math.max(1, (int) (sampleSize * EXPLORATION_FRACTION));
        int disagreementBudget = sampleSize - explorationBudget;

        // Cap to available pools
        disagreementBudget = Math.min(disagreementBudget, disagreements.size());
        explorationBudget = Math.min(explorationBudget, agreements.size());

        // If few disagreements, give remaining budget to exploration
        if (disagreementBudget < sampleSize - explorationBudget && !agreements.isEmpty()) {
            explorationBudget = Math.min(sampleSize - disagreementBudget, agreements.size());
        }

        logger.info("Sampling {} disagreement + {} exploration cells from {} disagreements, {} agreements",
                disagreementBudget, explorationBudget, disagreements.size(), agreements.size());

        if (disagreementBudget == 0 && explorationBudget == 0) {
            logger.info("No cells to sample.");
            return List.of();
        }

        // ── 3. Weighted disagreement sampling (without replacement) ─────────
        List<String> sampled = new ArrayList<>(disagreementBudget + explorationBudget);

        if (disagreementBudget >= disagreements.size()) {
            // Take all disagreements
            Collections.shuffle(disagreements, rng);
            for (var wc : disagreements) sampled.add(wc.cellId());
        } else {
            Set<Integer> chosen = new HashSet<>();
            double remainingWeight = totalWeight;

            for (int s = 0; s < disagreementBudget; s++) {
                double dart = rng.nextDouble() * remainingWeight;
                double cumulative = 0;
                int pick = -1;

                for (int i = 0; i < disagreements.size(); i++) {
                    if (chosen.contains(i)) continue;
                    cumulative += disagreements.get(i).weight();
                    if (cumulative >= dart) {
                        pick = i;
                        break;
                    }
                }

                if (pick < 0) {
                    for (int i = disagreements.size() - 1; i >= 0; i--) {
                        if (!chosen.contains(i)) { pick = i; break; }
                    }
                }

                if (pick >= 0) {
                    chosen.add(pick);
                    sampled.add(disagreements.get(pick).cellId());
                    remainingWeight -= disagreements.get(pick).weight();
                }
            }
        }

        // ── 4. Random exploration of agreement cells ────────────────────────
        if (explorationBudget > 0) {
            Collections.shuffle(agreements, rng);
            for (int i = 0; i < explorationBudget; i++) {
                sampled.add(agreements.get(i));
            }
        }

        logger.info("Sampled {} cells for review ({} disagreement, {} exploration)",
                sampled.size(), sampled.size() - explorationBudget, explorationBudget);
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
