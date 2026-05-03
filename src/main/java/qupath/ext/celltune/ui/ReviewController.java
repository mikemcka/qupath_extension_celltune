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
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.ROIs;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controls the review queue.
 *
 * Supports cross-image review batches by opening the correct project image
 * for each sampled cell before navigation/highlighting.
 */
public class ReviewController {

    private static final Logger logger = LoggerFactory.getLogger(ReviewController.class);
    private static final AtomicInteger ACTIVE_REVIEW_SESSIONS = new AtomicInteger(0);

    private static final class ReviewItem {
        private final String cellId;
        private final String imageName;

        private ReviewItem(String cellId, String imageName) {
            this.cellId = cellId;
            this.imageName = imageName;
        }
    }

    private final QuPathGUI qupath;
    private final List<ReviewItem> reviewItems;
    private final PopulationSet predictions;
    private final LabelStore outputLabels;
    private final Map<String, String> cellIdToImageName;
    private final Map<String, ProjectImageEntry<BufferedImage>> entryByImageName;

    private final Map<String, PathObject> currentImageCellCache = new LinkedHashMap<>();
    private String cachedImageName;

    /**
     * Background single-thread executor used to prefetch the next image's data so that
     * its tile cache is warm before the user switches to it. Daemon thread so it never
     * blocks JVM shutdown.
     */
    private final ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CellTune-ReviewPrefetch");
        t.setDaemon(true);
        return t;
    });
    /** Image name most recently submitted for prefetch, to avoid duplicate work. */
    private volatile String lastPrefetchedImageName;

    private int currentIndex = -1;
    private PathObject highlightMarker;
    private qupath.lib.objects.hierarchy.PathObjectHierarchy highlightHierarchy;
    private boolean sessionClosed;

    /**
     * Backward-compatible constructor for current-image review batches.
     */
    public ReviewController(QuPathGUI qupath,
                            List<String> cellIds,
                            PopulationSet predictions) {
        this(qupath, cellIds, predictions, Map.of());
    }

    /**
     * Create a review controller.
     *
     * @param qupath      QuPath GUI instance
     * @param cellIds     ordered cell IDs from sampler
     * @param predictions prediction set containing sampled cells
     * @param cellImageMap optional mapping from cell ID to image name
     */
    public ReviewController(QuPathGUI qupath,
                            List<String> cellIds,
                            PopulationSet predictions,
                            Map<String, String> cellImageMap) {
        this.qupath = qupath;
        this.predictions = predictions;
        this.outputLabels = new LabelStore("ReviewOutput");
        this.cellIdToImageName = new LinkedHashMap<>();
        this.entryByImageName = new LinkedHashMap<>();
        this.reviewItems = new ArrayList<>();

        String defaultImageName = currentImageName();

        if (qupath.getProject() != null) {
            @SuppressWarnings("unchecked")
            var entries = (List<ProjectImageEntry<BufferedImage>>) (List<?>) qupath.getProject().getImageList();
            for (var entry : entries) {
                if (entry != null && entry.getImageName() != null) {
                    entryByImageName.put(entry.getImageName(), entry);
                }
            }
        }

        if (cellImageMap != null) {
            cellIdToImageName.putAll(cellImageMap);
        }

        int unresolvedImageCount = 0;
        // Group cell IDs by image while preserving the within-image order from the
        // sampler. This means the user reviews ALL cells from image A before moving
        // to image B, which collapses N image switches into K (where K = number of
        // distinct images) and dramatically reduces cold-open overhead.
        Map<String, List<String>> idsByImage = new LinkedHashMap<>();
        for (String id : cellIds) {
            if (id == null || id.isBlank()) {
                continue;
            }

            String imageName = cellIdToImageName.get(id);
            if (imageName == null || imageName.isBlank()) {
                imageName = defaultImageName;
                unresolvedImageCount++;
            }
            if (imageName == null || imageName.isBlank()) {
                continue;
            }

            cellIdToImageName.putIfAbsent(id, imageName);
            idsByImage.computeIfAbsent(imageName, k -> new ArrayList<>()).add(id);
        }

        // Put the currently-open image first so the session starts without a switch.
        if (defaultImageName != null && idsByImage.containsKey(defaultImageName)) {
            List<String> firstBatch = idsByImage.remove(defaultImageName);
            for (String id : firstBatch) {
                reviewItems.add(new ReviewItem(id, defaultImageName));
            }
        }
        for (var entry : idsByImage.entrySet()) {
            for (String id : entry.getValue()) {
                reviewItems.add(new ReviewItem(id, entry.getKey()));
            }
        }

        Set<String> imageSet = new LinkedHashSet<>();
        for (var item : reviewItems) {
            imageSet.add(item.imageName);
        }

        ACTIVE_REVIEW_SESSIONS.incrementAndGet();

        logger.info("Review queue: {} cells across {} image(s) ({} used default image mapping)",
                reviewItems.size(), imageSet.size(), unresolvedImageCount);
    }

    /**  true when at least one review session is open */
    public static boolean isReviewSessionActive() {
        return ACTIVE_REVIEW_SESSIONS.get() > 0;
    }

    /** @return total number of cells in the review queue */
    public int size() {
        return reviewItems.size();
    }

    /** @return current 0-based index, or -1 if not started */
    public int getCurrentIndex() {
        return currentIndex;
    }

    /** @return current cell ID, or null */
    public String getCurrentCellId() {
        ReviewItem item = getCurrentItem();
        return item == null ? null : item.cellId;
    }

    /** @return current sampled cell image name, or null */
    public String getCurrentCellImageName() {
        ReviewItem item = getCurrentItem();
        return item == null ? null : item.imageName;
    }

    /** @return current PathObject, or null if unresolved */
    public PathObject getCurrentCell() {
        String cellId = getCurrentCellId();
        if (cellId == null) {
            return null;
        }
        if (!ensureImageOpen(getCurrentCellImageName())) {
            return null;
        }
        return resolveCellInCurrentImage(cellId);
    }

    /** @return prediction for current cell, or null */
    public CellPrediction getCurrentPrediction() {
        String cellId = getCurrentCellId();
        if (cellId == null) {
            return null;
        }
        return predictions.get(cellId);
    }

    /** @return review output labels */
    public LabelStore getOutputLabels() {
        return outputLabels;
    }

    /** @return true after advancing beyond last cell */
    public boolean isFinished() {
        return currentIndex >= reviewItems.size();
    }

    /**
     * Move to next cell and navigate there.
     */
    public boolean next() {
        if (currentIndex + 1 >= reviewItems.size()) {
            currentIndex = reviewItems.size();
            return false;
        }
        currentIndex++;
        navigateToCurrentCell();
        return true;
    }

    /**
     * Move to previous cell.
     */
    public boolean previous() {
        if (currentIndex <= 0) {
            return false;
        }
        currentIndex--;
        navigateToCurrentCell();
        return true;
    }

    /**
     * Jump to a specific 0-based index.
     */
    public boolean jumpTo(int index) {
        if (index < 0 || index >= reviewItems.size()) {
            return false;
        }
        currentIndex = index;
        navigateToCurrentCell();
        return true;
    }

    /**
     * Assign label to current cell and advance.
     */
    public boolean labelAndNext(String className) {
        String cellId = getCurrentCellId();
        if (cellId != null) {
            outputLabels.setLabel(cellId, className);
        }

        PathObject cell = getCurrentCell();
        if (cell != null) {
            cell.setPathClass(PathClass.fromString(className));
        }

        return next();
    }

    /**
     * Skip current cell and advance.
     */
    public boolean skip() {
        return next();
    }

    /** @return number of labels assigned in this session */
    public int getLabelledCount() {
        return outputLabels.size();
    }

    /** @return number of reviewed cells (labelled or skipped) */
    public int getReviewedCount() {
        return Math.max(0, Math.min(currentIndex + 1, reviewItems.size()));
    }

    /**
     * Return all class names available in the current QuPath project.
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

    private void navigateToCurrentCell() {
        ReviewItem item = getCurrentItem();
        if (item == null) {
            return;
        }

        if (!ensureImageOpen(item.imageName)) {
            logger.warn("Could not open image '{}' for sampled cell {}", item.imageName, item.cellId);
            return;
        }

        var viewer = qupath.getViewer();
        if (viewer == null) {
            return;
        }

        PathObject cell = resolveCellInCurrentImage(item.cellId);
        if (cell == null) {
            logger.warn("Could not resolve sampled cell {} in image '{}'", item.cellId, item.imageName);
            return;
        }

        var roi = cell.getROI();
        if (roi == null) {
            return;
        }

        // Set the zoom factor BEFORE centering so QuPath paints at the final scale on
        // first frame instead of rendering the wide overview and zooming in afterwards.
        // Target downsample sized so the cell occupies ~80px on screen.
        double cellSize = Math.max(roi.getBoundsWidth(), roi.getBoundsHeight());
        if (cellSize > 0) {
            double targetDownsample = Math.max(0.25, cellSize / 80.0);
            viewer.setDownsampleFactor(targetDownsample);
        }
        viewer.setCenterPixelLocation(roi.getCentroidX(), roi.getCentroidY());

        // Kick off a background read of the next image's ImageData so its tile cache
        // is warmed by the time the user navigates there. No-op if already prefetched.
        prefetchNextImageIfNeeded();

        var imageData = viewer.getImageData();
        if (imageData == null) {
            return;
        }

        var hierarchy = imageData.getHierarchy();
        hierarchy.getSelectionModel().setSelectedObject(cell);

        clearHighlightMarker();

        double cx = roi.getCentroidX();
        double cy = roi.getCentroidY();
        double r = Math.max(roi.getBoundsWidth(), roi.getBoundsHeight()) * 0.8;
        if (r < 15) {
            r = 15;
        }

        var highlightRoi = ROIs.createEllipseROI(
                cx - r, cy - r, r * 2, r * 2, roi.getImagePlane());
        var highlightClass = PathClass.fromString("CellTune-Highlight",
                (255 << 16) | (0 << 8) | 255);
        highlightMarker = PathObjects.createAnnotationObject(highlightRoi, highlightClass);
        highlightMarker.setLocked(true);
        hierarchy.addObject(highlightMarker);
        highlightHierarchy = hierarchy;

        logger.debug("Navigated to cell {} in image '{}' ({}/{})",
                item.cellId, item.imageName, currentIndex + 1, reviewItems.size());
    }

    /**
     * Remove the highlight marker.
     */
    public void removeHighlight() {
        clearHighlightMarker();
        closeSession();
    }

    private void clearHighlightMarker() {
        if (highlightMarker != null && highlightHierarchy != null) {
            highlightHierarchy.removeObject(highlightMarker, false);
        }
        highlightMarker = null;
        highlightHierarchy = null;
    }

    private void closeSession() {
        if (sessionClosed) {
            return;
        }
        sessionClosed = true;
        ACTIVE_REVIEW_SESSIONS.updateAndGet(value -> Math.max(0, value - 1));
        prefetchExecutor.shutdownNow();
    }

    private ReviewItem getCurrentItem() {
        if (currentIndex < 0 || currentIndex >= reviewItems.size()) {
            return null;
        }
        return reviewItems.get(currentIndex);
    }

    private boolean ensureImageOpen(String targetImageName) {
        if (targetImageName == null || targetImageName.isBlank()) {
            return qupath.getImageData() != null;
        }

        String current = currentImageName();
        if (targetImageName.equals(current)) {
            return true;
        }

        var entry = entryByImageName.get(targetImageName);
        if (entry == null) {
            logger.warn("Image '{}' not found in project entries", targetImageName);
            return false;
        }

        // Save the currently-open image's data BEFORE QuPath has a chance to prompt.
        // openImageEntry() shows a save dialog when the active hierarchy is dirty; by
        // persisting first we make the switch silent and still preserve user work.
        saveCurrentImageDataQuietly();

        clearHighlightMarker();

        boolean opened = qupath.openImageEntry(entry);
        if (!opened) {
            logger.warn("QuPath could not open image '{}'", targetImageName);
            return false;
        }

        currentImageCellCache.clear();
        cachedImageName = null;
        return true;
    }

    /**
     * Persist the active image's hierarchy without showing a dialog, so that the next
     * call to {@link QuPathGUI#openImageEntry} won't trigger QuPath's "save changes?"
     * prompt mid-review.
     */
    private void saveCurrentImageDataQuietly() {
        var project = qupath.getProject();
        var imageData = qupath.getImageData();
        if (project == null || imageData == null) {
            return;
        }
        var entry = project.getEntry(imageData);
        if (entry == null) {
            return;
        }
        try {
            entry.saveImageData(imageData);
        } catch (Exception ex) {
            logger.warn("Auto-save of '{}' before image switch failed: {}",
                    entry.getImageName(), ex.getMessage());
        }
    }

    /**
     * If the next reviewable cell is in a different image than the current one, submit
     * a background task to read that image's data so QuPath's tile cache is warmed by
     * the time {@link #ensureImageOpen} is called.
     */
    private void prefetchNextImageIfNeeded() {
        if (sessionClosed || prefetchExecutor.isShutdown()) {
            return;
        }
        int nextIdx = currentIndex + 1;
        if (nextIdx >= reviewItems.size()) {
            return;
        }
        ReviewItem nextItem = reviewItems.get(nextIdx);
        String nextName = nextItem == null ? null : nextItem.imageName;
        if (nextName == null || nextName.isBlank()) {
            return;
        }
        String currentName = currentImageName();
        if (nextName.equals(currentName)) {
            return;
        }
        if (nextName.equals(lastPrefetchedImageName)) {
            return;
        }
        var entry = entryByImageName.get(nextName);
        if (entry == null) {
            return;
        }
        lastPrefetchedImageName = nextName;
        prefetchExecutor.submit(() -> {
            try {
                // Reading the ImageData warms the project entry's data path; QuPath's tile
                // cache will then have headers/metadata ready when openImageEntry runs on
                // the FX thread. We don't keep a reference — GC reclaims it shortly.
                entry.readImageData();
                logger.debug("Prefetched ImageData for next review image '{}'", nextName);
            } catch (Throwable t) {
                // Prefetch is purely an optimisation; failures are non-fatal.
                logger.debug("Prefetch of '{}' failed: {}", nextName, t.getMessage());
            }
        });
    }

    private PathObject resolveCellInCurrentImage(String cellId) {
        var imageData = qupath.getImageData();
        if (imageData == null || cellId == null) {
            return null;
        }

        String imageName = currentImageName();
        if (imageName == null || !imageName.equals(cachedImageName)) {
            rebuildCurrentImageCellCache();
            cachedImageName = imageName;
        }

        return currentImageCellCache.get(cellId);
    }

    private void rebuildCurrentImageCellCache() {
        currentImageCellCache.clear();
        var imageData = qupath.getImageData();
        if (imageData == null) {
            return;
        }

        for (PathObject det : imageData.getHierarchy().getDetectionObjects()) {
            currentImageCellCache.put(det.getID().toString(), det);
        }
    }

    private String currentImageName() {
        var project = qupath.getProject();
        var imageData = qupath.getImageData();
        if (project == null || imageData == null) {
            return null;
        }

        var entry = project.getEntry(imageData);
        if (entry == null) {
            return null;
        }
        return entry.getImageName();
    }
}
