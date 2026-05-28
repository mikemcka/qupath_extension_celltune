package qupath.ext.celltune.ui;

import javafx.scene.control.CheckBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.model.CellPrediction;
import qupath.ext.celltune.model.CellTypeTable;
import qupath.lib.gui.QuPathGUI;

import java.util.List;
import java.util.ResourceBundle;

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

    private static final ResourceBundle STRINGS =
            ResourceBundle.getBundle("qupath.ext.celltune.ui.strings");

    private final QuPathGUI qupath;
    private final CellTypeTable cellTypeTable;
    private final CheckBox autoSwitchCheckBox;

    /**
     * @param qupath        the QuPath instance
     * @param cellTypeTable the cell-type → marker mapping (may be null)
     */
    public ChannelSelector(QuPathGUI qupath, CellTypeTable cellTypeTable) {
        this.qupath = qupath;
        this.cellTypeTable = cellTypeTable;
        this.autoSwitchCheckBox = new CheckBox(STRINGS.getString("sample.autochannel.label"));
        this.autoSwitchCheckBox.setSelected(true);
    }

    /** @return the checkbox that gates auto-switching; add it to your UI */
    public CheckBox getCheckBox() { return autoSwitchCheckBox; }

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

            logger.info("Auto-switch: looking for markers {} in {} channels",
                    markerNames, channels.size());

            for (var ch : channels) {
                String chName = ch.getName();
                boolean shouldShow = markerNames.stream()
                        .anyMatch(m -> chName.toLowerCase().contains(m.toLowerCase()));
                if (shouldShow) {
                    logger.info("  Showing channel: {}", chName);
                }
                display.setChannelSelected(ch, shouldShow);
                if (shouldShow) {
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
}
