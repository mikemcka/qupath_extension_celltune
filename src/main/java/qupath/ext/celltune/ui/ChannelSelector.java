package qupath.ext.celltune.ui;

import java.util.List;
import java.util.ResourceBundle;
import javafx.scene.control.CheckBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.model.CellPrediction;
import qupath.ext.celltune.model.CellTypeTable;
import qupath.lib.gui.QuPathGUI;

/**
 * Optional auto-channel switching during review.
 *
 * <p>When enabled and a {@link CellTypeTable} is loaded, this sets the
 * channel visibility based on the predicted cell type's associated markers
 * (up to {@link CellTypeTable#MAX_MARKERS}) each time the reviewer advances
 * to a new cell.
 *
 * <p>When disabled — or when no CellTypeTable is loaded — the viewer's
 * channel settings are left untouched (the user switches manually).
 */
public class ChannelSelector {

    private static final Logger logger = LoggerFactory.getLogger(ChannelSelector.class);

    private static final ResourceBundle STRINGS = ResourceBundle.getBundle("qupath.ext.celltune.ui.strings");

    private final QuPathGUI qupath;
    private final CellTypeTable cellTypeTable;
    private final CheckBox autoSwitchCheckBox;
    private final CheckBox autoDisplayRangeCheckBox;

    /**
     * @param qupath        the QuPath instance
     * @param cellTypeTable the cell-type → marker mapping (may be null)
     */
    public ChannelSelector(QuPathGUI qupath, CellTypeTable cellTypeTable) {
        this.qupath = qupath;
        this.cellTypeTable = cellTypeTable;
        this.autoSwitchCheckBox = new CheckBox(STRINGS.getString("sample.autochannel.label"));
        this.autoSwitchCheckBox.setSelected(true);

        // Sub-option: whether newly-shown channels also get their brightness/contrast
        // (display range) auto-adjusted. Off by default so the user's existing display
        // settings are preserved; only channel visibility changes unless this is ticked.
        this.autoDisplayRangeCheckBox = new CheckBox(STRINGS.getString("sample.autochannel.displayrange.label"));
        this.autoDisplayRangeCheckBox.setSelected(false);
        // The display-range adjustment only happens as part of channel switching, so grey
        // it out when auto-switching itself is disabled.
        this.autoDisplayRangeCheckBox
                .disableProperty()
                .bind(autoSwitchCheckBox.selectedProperty().not());
    }

    /** @return the checkbox that gates auto-switching; add it to your UI */
    public CheckBox getCheckBox() {
        return autoSwitchCheckBox;
    }

    /**
     * @return the checkbox that gates auto-adjusting brightness/contrast (display
     *     range) of shown channels; add it to your UI. Off by default.
     */
    public CheckBox getDisplayRangeCheckBox() {
        return autoDisplayRangeCheckBox;
    }

    /**
     * Apply channel switching for the current cell in the given review controller.
     * <p>
     * Does nothing if the checkbox is unchecked, if no CellTypeTable is loaded,
     * or if the predicted cell type has no associated markers.
     */
    public void applyForCurrentCell(ReviewController controller) {
        if (!autoSwitchCheckBox.isSelected()) return;
        if (cellTypeTable == null) return;

        CellPrediction pred = controller.getCurrentPrediction();
        if (pred == null) return;

        String predictedType = pred.avgLabel();
        if (predictedType == null) return;

        List<String> markers = cellTypeTable.getMarkers(predictedType);
        if (markers == null || markers.isEmpty()) return;

        applyChannels(markers);
    }

    /**
     * Set only the given marker channels visible, hiding all others.
     */
    private void applyChannels(List<String> markerNames) {
        var viewer = qupath.getViewer();
        if (viewer == null) return;

        try {
            var display = viewer.getImageDisplay();
            if (display == null) return;

            var channels = display.availableChannels();
            if (channels == null) return;

            // Pre-normalize the marker names once
            List<String> normMarkers = markerNames.stream()
                    .map(ChannelSelector::alphanumericNormalize)
                    .filter(m -> !m.isEmpty())
                    .toList();

            logger.info("Auto-switch: looking for markers {} in {} channels", markerNames, channels.size());

            for (var ch : channels) {
                String normCh = alphanumericNormalize(ch.getName());
                boolean shouldShow = normMarkers.stream()
                        .anyMatch(m -> normCh.equals(m) || normCh.contains(m) || m.contains(normCh));
                if (shouldShow) {
                    logger.info("  Showing channel: {}", ch.getName());
                }
                display.setChannelSelected(ch, shouldShow);
                if (shouldShow && autoDisplayRangeCheckBox.isSelected()) {
                    display.autoSetDisplayRange(ch);
                }
            }

            // Force the viewer to repaint with the updated channel visibility
            viewer.repaintEntireImage();

            logger.debug("Auto-switched channels to: {}", markerNames);
        } catch (Exception e) {
            // If the display API is unavailable or throws, fall back silently
            logger.warn("Could not auto-switch channels: {}", e.getMessage());
        }
    }

    /**
     * Normalize a channel/marker name to lowercase alphanumeric characters only.
     * This makes matching robust to variations in spacing, dashes, underscores,
     * and parentheses that can differ between marker table CSVs and QuPath channel names.
     * <p>
     * Examples: "CD3_S2 - Cy5_AF" → "cd3s2cy5af",
     *           "CD3_S2-Cy5_AF" → "cd3s2cy5af" (same result).
     */
    private static String alphanumericNormalize(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
