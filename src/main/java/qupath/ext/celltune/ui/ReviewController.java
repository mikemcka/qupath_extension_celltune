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

    /** When non-null/non-empty, navigation displays small training tiles instead of switching project images. */
    private final Map<String, TrainingTileExtractor.TilePrep> tilePreps;
    private final boolean tileMode;
    /** Currently-displayed tile ImageData (held so we can close it on swap). */
    private qupath.lib.images.ImageData<BufferedImage> currentTileImageData;
    /** Cell ID currently shown as a tile, used to avoid rebuilding when re-entering the same cell. */
    private String currentTileCellId;
    /** Project entry that was open when the review session started; restored on close in tile mode. */
    private final ProjectImageEntry<BufferedImage> initialEntry;
    /**
     * Snapshot of project entry references taken when the review session started.
     * Used to detect and remove any entries that QuPath's ProjectBrowser may have
     * auto-added in response to {@code viewer.setImageData(...)} being called with
     * a CroppedImageServer-backed ImageData (entries shaped like
     * "crop_1.ome.tiff (x, y, w, h)"). Identity-based to avoid relying on names.
     */
    private final java.util.Set<ProjectImageEntry<BufferedImage>> initialEntrySnapshot = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

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
    /**
     * Strong reference to the most recently prefetched ImageData. Holding it prevents
     * the GC from reclaiming the warm tile cache, decoded TIFF headers, and parsed
     * detection hierarchy before QuPath's {@code openImageEntry} actually consumes it.
     * Cleared after the user switches to that image (or to another image).
     */
    private volatile qupath.lib.images.ImageData<BufferedImage> prefetchedImageData;

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
        this(qupath, cellIds, predictions, Map.of(), null);
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
        this(qupath, cellIds, predictions, cellImageMap, null);
    }

    /**
     * Create a review controller, optionally backed by pre-extracted training tiles.
     *
     * <p>When {@code tilePreps} is non-null and non-empty, navigation no longer switches
     * QuPath project images; instead each cell is shown in a small
     * {@link qupath.lib.images.servers.CroppedImageServer}-backed ImageData.
     * Cells without a corresponding prep fall back to the project-image flow.
     *
     * @param qupath        QuPath GUI instance
     * @param cellIds       ordered cell IDs from sampler
     * @param predictions   prediction set containing sampled cells
     * @param cellImageMap  cellId → image name mapping
     * @param tilePreps     optional pre-extracted training tiles, keyed by cellId
     */
    public ReviewController(QuPathGUI qupath,
                            List<String> cellIds,
                            PopulationSet predictions,
                            Map<String, String> cellImageMap,
                            Map<String, TrainingTileExtractor.TilePrep> tilePreps) {
        this.qupath = qupath;
        this.predictions = predictions;
        this.outputLabels = new LabelStore("ReviewOutput");
        this.cellIdToImageName = new LinkedHashMap<>();
        this.entryByImageName = new LinkedHashMap<>();
        this.reviewItems = new ArrayList<>();
        this.tilePreps = tilePreps;
        this.tileMode = tilePreps != null && !tilePreps.isEmpty();

        ProjectImageEntry<BufferedImage> initial = null;
        if (qupath.getProject() != null && qupath.getImageData() != null) {
            initial = qupath.getProject().getEntry(qupath.getImageData());
        }
        this.initialEntry = initial;

        // Snapshot project entries BEFORE we touch the viewer, so we can later
        // identify any tile-derived entries QuPath's ProjectBrowser auto-added.
        if (qupath.getProject() != null) {
            @SuppressWarnings("unchecked")
            var allEntries = (List<ProjectImageEntry<BufferedImage>>) (List<?>) qupath.getProject().getImageList();
            initialEntrySnapshot.addAll(allEntries);
        }

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

        if (tileMode) {
            // Drop the displayed tile and restore the original project image so the
            // user is returned to the QuPath state they started from.
            var oldTile = currentTileImageData;
            currentTileImageData = null;
            currentTileCellId = null;
            if (oldTile != null) {
                try { oldTile.setChanged(false); } catch (Exception ignored) { }
            }
            try {
                if (initialEntry != null) {
                    qupath.openImageEntry(initialEntry);
                } else {
                    var viewer = qupath.getViewer();
                    if (viewer != null) viewer.resetImageData();
                }
            } catch (Exception ex) {
                logger.debug("Could not restore original image after tile review: {}", ex.getMessage());
            }
            if (oldTile != null) {
                try { oldTile.close(); } catch (Exception ignored) { }
            }

            // Remove any tile-derived project entries that QuPath's ProjectBrowser
            // auto-added while we were swapping CroppedImageServer ImageData into the
            // viewer. They are temporary review artifacts the user does not want
            // persisted.
            removeAutoAddedTileEntries();
        }
    }

    private ReviewItem getCurrentItem() {
        if (currentIndex < 0 || currentIndex >= reviewItems.size()) {
            return null;
        }
        return reviewItems.get(currentIndex);
    }

    private boolean ensureImageOpen(String targetImageName) {
        if (tileMode) {
            return ensureTileOpenForCurrentItem();
        }
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

        clearHighlightMarker();

        // Mark the current image's hierarchy clean so QuPath's openImageEntry()
        // doesn't show a "save changes?" dialog. Must be done AFTER clearHighlightMarker
        // (which removes an annotation and re-dirties the hierarchy) and immediately
        // before openImageEntry, so no further hierarchy events can flip the flag back.
        // We do NOT actually write the .qpdata file — saving is slow on big images and
        // unnecessary, because review labels are kept in ReviewController.outputLabels
        // (in-memory) and persisted at session end via persistReviewedLabelsByImage().
        markCurrentImageCleanForSwitch();

        boolean opened = qupath.openImageEntry(entry);
        if (!opened) {
            logger.warn("QuPath could not open image '{}'", targetImageName);
            return false;
        }

        // Drop the strong reference now that QuPath has loaded the image — we don't
        // want to pin two big ImageData instances in memory once the user has moved on.
        prefetchedImageData = null;

        currentImageCellCache.clear();
        cachedImageName = null;
        return true;
    }

    /**
     * Clear the "dirty" flag on the active image's hierarchy so the next call to
     * {@link QuPathGUI#openImageEntry} won't trigger QuPath's "save changes?" prompt.
     * <p>
     * We deliberately do NOT call {@code entry.saveImageData(...)} — that's slow on
     * large images, and the review workflow doesn't need it: review labels live in
     * {@link #outputLabels} (in-memory) and are written to disk at the end of the
     * session by {@code persistReviewedLabelsByImage()}.
     */
    private void markCurrentImageCleanForSwitch() {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            return;
        }
        try {
            imageData.setChanged(false);
        } catch (Exception ex) {
            logger.warn("Failed to clear dirty flag before image switch: {}", ex.getMessage());
        }
    }

    /**
     * If a later reviewable cell is in a different image than the current one, submit
     * a background task to read that image's data so QuPath's tile cache is warmed by
     * the time {@link #ensureImageOpen} is called.
     * <p>
     * Looks forward through the entire remaining queue (not just the next item) so
     * prefetch starts as soon as the user lands on a new image, giving the background
     * thread maximum time to load the next image's data.
     */
    private void prefetchNextImageIfNeeded() {
        if (tileMode) {
            return; // tiles are pre-extracted; nothing to do
        }
        if (sessionClosed || prefetchExecutor.isShutdown()) {
            return;
        }
        String currentName = currentImageName();

        // Find the next item whose image differs from the current one.
        String nextName = null;
        for (int idx = currentIndex + 1; idx < reviewItems.size(); idx++) {
            ReviewItem item = reviewItems.get(idx);
            if (item == null || item.imageName == null || item.imageName.isBlank()) continue;
            if (item.imageName.equals(currentName)) continue;
            nextName = item.imageName;
            break;
        }
        if (nextName == null) {
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
        final String prefetchTarget = nextName;
        prefetchExecutor.submit(() -> {
            try {
                // Reading the ImageData warms QuPath's caches (TIFF headers, detection
                // hierarchy, tile cache). We hold a strong reference in
                // prefetchedImageData so the GC doesn't reclaim those caches before
                // openImageEntry actually consumes them.
                var data = entry.readImageData();
                prefetchedImageData = data;
                logger.debug("Prefetched ImageData for next review image '{}'", prefetchTarget);
            } catch (Throwable t) {
                // Prefetch is purely an optimisation; failures are non-fatal.
                logger.debug("Prefetch of '{}' failed: {}", prefetchTarget, t.getMessage());
            }
        });
    }

    private PathObject resolveCellInCurrentImage(String cellId) {
        if (tileMode) {
            var prep = tilePreps == null ? null : tilePreps.get(cellId);
            return prep == null ? null : prep.highlightCell();
        }
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

    /**
     * Tile-mode equivalent of {@link #ensureImageOpen(String)}: builds a fresh
     * tile ImageData (CroppedImageServer + mini hierarchy) for the current cell
     * and swaps it into the active viewer, replacing whatever was previously shown.
     */
    private boolean ensureTileOpenForCurrentItem() {
        ReviewItem item = getCurrentItem();
        if (item == null) return false;
        if (tilePreps == null) return false;
        var prep = tilePreps.get(item.cellId);
        if (prep == null) {
            logger.warn("No training tile available for cell {} (image '{}')", item.cellId, item.imageName);
            return false;
        }
        if (item.cellId.equals(currentTileCellId) && currentTileImageData != null) {
            return true; // already showing this tile
        }

        var viewer = qupath.getViewer();
        if (viewer == null) return false;

        clearHighlightMarker();
        var newData = TrainingTileExtractor.buildTileImageData(prep);
        try {
            // Mark the prior ImageData clean so QuPath does not prompt.
            var prior = viewer.getImageData();
            if (prior != null) {
                try { prior.setChanged(false); } catch (Exception ignored) { }
            }
            viewer.setImageData(newData);
        } catch (java.io.IOException ex) {
            logger.warn("Failed to set training tile ImageData: {}", ex.getMessage());
            return false;
        }

        // Close the previously-shown tile ImageData (we don't reuse it; reopening rebuilds).
        var oldTile = currentTileImageData;
        currentTileImageData = newData;
        currentTileCellId = item.cellId;
        if (oldTile != null && oldTile != newData) {
            try { oldTile.close(); } catch (Exception ignored) { }
        }
        return true;
    }

    /**
     * Remove any project entries that QuPath added while the review session was
     * active. Compares against {@link #initialEntrySnapshot} taken before the
     * first {@code viewer.setImageData(tile)} call; anything new must be a
     * tile-derived auto-added entry (named like {@code crop_1.ome.tiff (x, y, w, h)}).
     */
    private void removeAutoAddedTileEntries() {
        var project = qupath.getProject();
        if (project == null) return;
        try {
            @SuppressWarnings("unchecked")
            var current = (List<ProjectImageEntry<BufferedImage>>) (List<?>) project.getImageList();
            List<ProjectImageEntry<BufferedImage>> toRemove = new ArrayList<>();
            for (var entry : current) {
                if (entry == null) continue;
                if (!initialEntrySnapshot.contains(entry)) {
                    toRemove.add(entry);
                }
            }
            if (toRemove.isEmpty()) return;
            for (var entry : toRemove) {
                try {
                    project.removeImage(entry, true);
                    logger.info("Removed auto-added tile project entry: {}", entry.getImageName());
                } catch (Exception ex) {
                    logger.warn("Failed to remove auto-added tile entry '{}': {}",
                            entry.getImageName(), ex.getMessage());
                }
            }
            try {
                project.syncChanges();
            } catch (Exception ex) {
                logger.warn("Failed to sync project after removing tile entries: {}", ex.getMessage());
            }
            // Refresh the project browser so removed entries disappear from the UI.
            javafx.application.Platform.runLater(() -> {
                try { qupath.refreshProject(); } catch (Exception ignored) { }
            });
        } catch (Exception ex) {
            logger.warn("Failed to scan project for auto-added tile entries: {}", ex.getMessage());
        }
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
