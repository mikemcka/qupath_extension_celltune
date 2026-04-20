package qupath.ext.celltune.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.model.CellPrediction;
import qupath.ext.celltune.model.LabelStore;
import qupath.ext.celltune.model.PopulationSet;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.util.*;

/**
 * Controls the review queue — an ordered list of cells to review one-by-one.
 * <p>
 * Manages the current index, assignable labels, and viewer navigation.
 * The review output is a {@link LabelStore} containing human-assigned labels
 * for reviewed cells, which gets merged back into the main label store
 * after the review session ends.
 */
public class ReviewController {

    private static final Logger logger = LoggerFactory.getLogger(ReviewController.class);

    private final QuPathGUI qupath;
    private final List<PathObject> reviewCells;
    private final PopulationSet predictions;
    private final LabelStore outputLabels;
    private int currentIndex = -1;
    private PathObject highlightMarker;

    /**
     * Create a review controller.
     *
     * @param qupath      the QuPath GUI instance
     * @param cellIds     ordered list of cell IDs to review (from UncertaintySampler)
     * @param predictions the Pred_ALL population set
     */
    public ReviewController(QuPathGUI qupath,
                            List<String> cellIds,
                            PopulationSet predictions) {
        this.qupath = qupath;
        this.predictions = predictions;
        this.outputLabels = new LabelStore("ReviewOutput");

        // Resolve cell IDs to PathObjects
        var imageData = qupath.getImageData();
        Map<String, PathObject> byId = new LinkedHashMap<>();
        if (imageData != null) {
            for (PathObject det : imageData.getHierarchy().getDetectionObjects()) {
                byId.put(det.getID().toString(), det);
            }
        }

        reviewCells = new ArrayList<>();
        for (String id : cellIds) {
            PathObject cell = byId.get(id);
            if (cell != null) {
                reviewCells.add(cell);
            }
        }

        logger.info("Review queue: {} cells (from {} requested)",
                reviewCells.size(), cellIds.size());
    }

    /** @return total number of cells in the review queue */
    public int size() { return reviewCells.size(); }

    /** @return current 0-based index, or -1 if not started */
    public int getCurrentIndex() { return currentIndex; }

    /** @return the current cell being reviewed, or null */
    public PathObject getCurrentCell() {
        if (currentIndex < 0 || currentIndex >= reviewCells.size()) return null;
        return reviewCells.get(currentIndex);
    }

    /** @return the prediction for the current cell, or null */
    public CellPrediction getCurrentPrediction() {
        PathObject cell = getCurrentCell();
        if (cell == null) return null;
        return predictions.get(cell.getID().toString());
    }

    /** @return the review output label store */
    public LabelStore getOutputLabels() { return outputLabels; }

    /** @return true if we've gone past the last cell */
    public boolean isFinished() {
        return currentIndex >= reviewCells.size();
    }

    // ── Navigation ──────────────────────────────────────────────────────────────

    /**
     * Move to the next cell and navigate the viewer there.
     *
     * @return true if there is a next cell, false if review is complete
     */
    public boolean next() {
        if (currentIndex + 1 >= reviewCells.size()) {
            currentIndex = reviewCells.size(); // mark finished
            return false;
        }
        currentIndex++;
        navigateToCurrentCell();
        return true;
    }

    /**
     * Move to the previous cell.
     *
     * @return true if there is a previous cell
     */
    public boolean previous() {
        if (currentIndex <= 0) return false;
        currentIndex--;
        navigateToCurrentCell();
        return true;
    }

    /**
     * Jump to a specific 0-based index.
     *
     * @param index the target index
     * @return true if the index is valid
     */
    public boolean jumpTo(int index) {
        if (index < 0 || index >= reviewCells.size()) return false;
        currentIndex = index;
        navigateToCurrentCell();
        return true;
    }

    /**
     * Assign a label to the current cell and advance to the next.
     *
     * @param className the class to assign
     * @return true if we moved to a next cell, false if review is complete
     */
    public boolean labelAndNext(String className) {
        PathObject cell = getCurrentCell();
        if (cell != null) {
            outputLabels.setLabel(cell.getID().toString(), className);
            // Also update the PathClass on the cell visually
            cell.setPathClass(PathClass.fromString(className));
        }
        return next();
    }

    /**
     * Skip the current cell (no label assigned) and advance.
     *
     * @return true if we moved to a next cell
     */
    public boolean skip() {
        return next();
    }

    /** @return number of cells that have been labelled so far in this session */
    public int getLabelledCount() {
        return outputLabels.size();
    }

    /** @return number of cells that have been reviewed (labelled or skipped) */
    public int getReviewedCount() {
        return Math.max(0, Math.min(currentIndex + 1, reviewCells.size()));
    }

    /**
     * Return all PathClass names available in the QuPath project, for the
     * "All Classes" popup in the review toolbar.
     */
    public List<String> getQuPathClassNames() {
        List<String> names = new ArrayList<>();
        var project = qupath.getProject();
        if (project != null) {
            for (var pc : project.getPathClasses()) {
                if (pc != null && pc.getName() != null && !pc.getName().isEmpty()) {
                    names.add(pc.getName());
                }
            }
        }
        return names;
    }

    // ── Viewer navigation ───────────────────────────────────────────────────────

    private void navigateToCurrentCell() {
        PathObject cell = getCurrentCell();
        if (cell == null) return;

        var viewer = qupath.getViewer();
        if (viewer == null) return;

        var roi = cell.getROI();
        if (roi == null) return;

        // Centre the viewer on the cell centroid
        viewer.setCenterPixelLocation(roi.getCentroidX(), roi.getCentroidY());

        // Select this cell in the hierarchy so it's highlighted
        var imageData = viewer.getImageData();
        if (imageData != null) {
            var hierarchy = imageData.getHierarchy();
            hierarchy.getSelectionModel().setSelectedObject(cell);

            // Remove previous highlight marker
            if (highlightMarker != null) {
                hierarchy.removeObject(highlightMarker, false);
                highlightMarker = null;
            }

            // Add a bright crosshair annotation at the cell centroid
            double cx = roi.getCentroidX();
            double cy = roi.getCentroidY();
            double r = Math.max(roi.getBoundsWidth(), roi.getBoundsHeight()) * 0.8;
            if (r < 15) r = 15;

            // Create an ellipse annotation around the cell as a highlight ring
            var highlightRoi = ROIs.createEllipseROI(
                    cx - r, cy - r, r * 2, r * 2, roi.getImagePlane());
            // Bright magenta for visibility: packed RGB = 0xFF00FF
            var highlightClass = PathClass.fromString("CellTune-Highlight",
                    (255 << 16) | (0 << 8) | 255);
            highlightMarker = PathObjects.createAnnotationObject(highlightRoi, highlightClass);
            highlightMarker.setLocked(true);
            hierarchy.addObject(highlightMarker);
        }

        logger.debug("Navigated to cell {} ({}/{})",
                cell.getID(), currentIndex + 1, reviewCells.size());
    }

    /**
     * Remove the highlight marker from the hierarchy.
     * Should be called when the review window closes.
     */
    public void removeHighlight() {
        if (highlightMarker != null) {
            var imageData = qupath.getImageData();
            if (imageData != null) {
                imageData.getHierarchy().removeObject(highlightMarker, false);
            }
            highlightMarker = null;
        }
    }
}
