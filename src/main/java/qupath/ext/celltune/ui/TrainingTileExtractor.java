package qupath.ext.celltune.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.CroppedImageServer;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Pre-extracts small "training tiles" around sampled cells so the review UI
 * can display them without paying the cost of switching between full project
 * images for every cell.
 *
 * <p>For each unique source image we open the {@link ImageData} once and reuse it
 * across every cell that came from that image. For each individual cell we build
 * a {@link CroppedImageServer} covering the cell's bounding box plus padding,
 * along with a small {@link qupath.lib.objects.hierarchy.PathObjectHierarchy}
 * containing translated copies of detections that fall inside the crop (so the
 * user sees spatial context). The displayed PathObject does NOT preserve the
 * original UUID — round-trip to the original cell is handled via
 * {@code ReviewController.getCurrentCellId()} which returns the cached sampler
 * cellId rather than reading it from the displayed object.
 *
 * <p>The extractor holds strong references to the source {@link ImageData}
 * instances; call {@link #close()} when the review session ends to release them.
 */
public final class TrainingTileExtractor implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(TrainingTileExtractor.class);

    /** Total region size = bounding box * (1 + 2 * PADDING_FACTOR) → 4x bbox at PADDING_FACTOR=1.5. */
    private static final double PADDING_FACTOR = 1.5;
    /** Floor on tile size in pixels so tiny cells still get a usable view. */
    private static final int MIN_TILE_PX = 96;

    /** A single pre-extracted tile description, ready to display. */
    public static final class TilePrep {
        final String cellId;
        final String imageName;
        final ImageData<BufferedImage> sourceImageData;
        final ImageRegion cropRegion;
        final List<PathObject> contextObjects;   // already translated to tile coords
        final PathObject highlightCell;          // member of contextObjects, in tile coords
        final ImageData.ImageType imageType;

        TilePrep(String cellId,
                 String imageName,
                 ImageData<BufferedImage> sourceImageData,
                 ImageRegion cropRegion,
                 List<PathObject> contextObjects,
                 PathObject highlightCell,
                 ImageData.ImageType imageType) {
            this.cellId = cellId;
            this.imageName = imageName;
            this.sourceImageData = sourceImageData;
            this.cropRegion = cropRegion;
            this.contextObjects = contextObjects;
            this.highlightCell = highlightCell;
            this.imageType = imageType;
        }

        public String cellId() { return cellId; }
        public String imageName() { return imageName; }
        public ImageRegion cropRegion() { return cropRegion; }
        public PathObject highlightCell() { return highlightCell; }
        public List<PathObject> contextObjects() { return contextObjects; }
        public ImageData<BufferedImage> sourceImageData() { return sourceImageData; }
        public ImageData.ImageType imageType() { return imageType; }
    }

    private final Map<String, TilePrep> preps = new LinkedHashMap<>();
    private final List<ImageData<BufferedImage>> openImageData = new ArrayList<>();

    private TrainingTileExtractor() {}

    /**
     * Build tiles for the given cell IDs.
     *
     * @param qupath          QuPath GUI (used to access the project)
     * @param cellIds         ordered list of cell IDs to extract
     * @param cellImageMap    cellId → image name mapping (cells without a mapping are skipped)
     * @param progress        optional progress callback (0..total); may be null
     * @return populated extractor; caller must close() when done
     */
    public static TrainingTileExtractor extract(QuPathGUI qupath,
                                                List<String> cellIds,
                                                Map<String, String> cellImageMap,
                                                IntConsumer progress) {
        var extractor = new TrainingTileExtractor();
        try {
            extractor.run(qupath, cellIds, cellImageMap, progress);
        } catch (Throwable t) {
            extractor.close();
            throw t;
        }
        return extractor;
    }

    private void run(QuPathGUI qupath,
                     List<String> cellIds,
                     Map<String, String> cellImageMap,
                     IntConsumer progress) {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            logger.warn("No project open; cannot extract training tiles");
            return;
        }

        // Index project entries by image name.
        Map<String, ProjectImageEntry<BufferedImage>> entryByName = new LinkedHashMap<>();
        for (var entry : project.getImageList()) {
            if (entry != null && entry.getImageName() != null) {
                entryByName.put(entry.getImageName(), entry);
            }
        }

        // Group cells by image so each ImageData is opened at most once.
        Map<String, List<String>> idsByImage = new LinkedHashMap<>();
        for (String id : cellIds) {
            if (id == null || id.isBlank()) continue;
            String img = cellImageMap == null ? null : cellImageMap.get(id);
            if (img == null || img.isBlank()) continue;
            idsByImage.computeIfAbsent(img, k -> new ArrayList<>()).add(id);
        }

        int processed = 0;
        int total = cellIds.size();

        for (var groupEntry : idsByImage.entrySet()) {
            String imageName = groupEntry.getKey();
            List<String> ids = groupEntry.getValue();
            ProjectImageEntry<BufferedImage> entry = entryByName.get(imageName);
            if (entry == null) {
                logger.warn("Project entry not found for image '{}'; skipping {} cell(s)", imageName, ids.size());
                processed += ids.size();
                if (progress != null) progress.accept(Math.min(processed, total));
                continue;
            }

            ImageData<BufferedImage> imageData;
            try {
                imageData = entry.readImageData();
            } catch (IOException ex) {
                logger.warn("Could not read ImageData for '{}': {}", imageName, ex.getMessage());
                processed += ids.size();
                if (progress != null) progress.accept(Math.min(processed, total));
                continue;
            }

            // getServer() lazy-opens the underlying file and may throw a
            // RuntimeException (e.g. when the source TIFF has been moved or
            // deleted). Treat it the same as readImageData failure: skip
            // every cell from this image instead of aborting the whole
            // review session.
            ImageServer<BufferedImage> server;
            int serverW;
            int serverH;
            ImageData.ImageType imageType;
            try {
                server = imageData.getServer();
                imageType = imageData.getImageType();
                serverW = server.getWidth();
                serverH = server.getHeight();
            } catch (Exception ex) {
                logger.warn("Could not open ImageServer for '{}' (file moved or deleted?): {}",
                        imageName, ex.getMessage());
                processed += ids.size();
                if (progress != null) progress.accept(Math.min(processed, total));
                continue;
            }
            openImageData.add(imageData);

            // Index detections by ID for fast cell lookup.
            Map<String, PathObject> detById = new LinkedHashMap<>();
            for (PathObject det : imageData.getHierarchy().getDetectionObjects()) {
                detById.put(det.getID().toString(), det);
            }

            for (String cellId : ids) {
                PathObject originalCell = detById.get(cellId);
                if (originalCell == null) {
                    logger.warn("Cell {} not found in image '{}'", cellId, imageName);
                    processed++;
                    if (progress != null) progress.accept(Math.min(processed, total));
                    continue;
                }
                ROI cellRoi = originalCell.getROI();
                if (cellRoi == null) {
                    processed++;
                    if (progress != null) progress.accept(Math.min(processed, total));
                    continue;
                }

                ImageRegion region = computeCropRegion(cellRoi, serverW, serverH);

                // Collect context detections inside the crop and translate to tile coords.
                ImagePlane tilePlane = ImagePlane.getPlaneWithChannel(0, 0, 0);
                List<PathObject> ctx = new ArrayList<>();
                PathObject highlight = null;
                double dx = -region.getX();
                double dy = -region.getY();

                for (PathObject det : imageData.getHierarchy().getAllDetectionsForRegion(region)) {
                    ROI r = det.getROI();
                    if (r == null) continue;
                    ROI translated;
                    try {
                        translated = r.translate(dx, dy);
                    } catch (Exception ex) {
                        continue;
                    }
                    PathClass pc = det.getPathClass();
                    PathObject copy;
                    if (det.isCell() && det.getROI() != null) {
                        // Approximate cell objects with detection objects in the tile;
                        // we only need them for visual context.
                        copy = PathObjects.createDetectionObject(translated, pc);
                    } else {
                        copy = PathObjects.createDetectionObject(translated, pc);
                    }
                    ctx.add(copy);
                    if (det == originalCell) {
                        highlight = copy;
                    }
                }

                if (highlight == null) {
                    // Defensive: ensure the sampled cell is always in the tile, even if
                    // getAllDetectionsForRegion missed it.
                    ROI translatedCellRoi = cellRoi.translate(dx, dy);
                    highlight = PathObjects.createDetectionObject(translatedCellRoi, originalCell.getPathClass());
                    ctx.add(highlight);
                }

                preps.put(cellId, new TilePrep(cellId, imageName, imageData, region, ctx, highlight, imageType));
                processed++;
                if (progress != null) progress.accept(Math.min(processed, total));
            }
        }

        logger.info("TrainingTileExtractor: built {} tile(s) from {} source image(s)",
                preps.size(), openImageData.size());
    }

    private static ImageRegion computeCropRegion(ROI cellRoi, int serverW, int serverH) {
        double w = cellRoi.getBoundsWidth();
        double h = cellRoi.getBoundsHeight();
        double size = Math.max(w, h);
        if (size <= 0) size = MIN_TILE_PX;
        double tileSize = Math.max(MIN_TILE_PX, size * (1 + 2 * PADDING_FACTOR));

        double cx = cellRoi.getCentroidX();
        double cy = cellRoi.getCentroidY();
        double half = tileSize / 2.0;

        int x = (int) Math.floor(Math.max(0, cx - half));
        int y = (int) Math.floor(Math.max(0, cy - half));
        int regionW = (int) Math.ceil(Math.min(serverW - x, tileSize));
        int regionH = (int) Math.ceil(Math.min(serverH - y, tileSize));
        regionW = Math.max(1, regionW);
        regionH = Math.max(1, regionH);

        int z = cellRoi.getZ();
        int t = cellRoi.getT();
        return ImageRegion.createInstance(x, y, regionW, regionH, z, t);
    }

    /** @return ordered map of cellId → TilePrep (insertion order matches the order extracted). */
    public Map<String, TilePrep> getPreps() {
        return preps;
    }

    /**
     * Build a fresh ImageData wrapping a CroppedImageServer for the given prep.
     * The returned ImageData is independent of any other tile's ImageData and
     * may be passed to {@code QuPathViewer.setImageData(...)}.
     */
    public static ImageData<BufferedImage> buildTileImageData(TilePrep prep) {
        ImageServer<BufferedImage> source = prep.sourceImageData.getServer();
        CroppedImageServer cropped = new CroppedImageServer(source, prep.cropRegion);

        var hierarchy = new qupath.lib.objects.hierarchy.PathObjectHierarchy();
        hierarchy.addObjects(prep.contextObjects);

        ImageData.ImageType type = prep.imageType == null ? ImageData.ImageType.UNSET : prep.imageType;
        return new ImageData<>(cropped, hierarchy, type);
    }

    @Override
    public void close() {
        for (var data : openImageData) {
            try {
                data.close();
            } catch (Exception ex) {
                logger.debug("Closing source ImageData failed: {}", ex.getMessage());
            }
        }
        openImageData.clear();
        preps.clear();
    }
}
